param([string]$ControllerUrl = 'http://localhost:8081')
$ErrorActionPreference = 'Stop'

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-Cluster {
    Invoke-RestMethod -Uri "$ControllerUrl/internal/status" -TimeoutSec 10
}
function Wait-Until([scriptblock]$Condition, [string]$Description, [int]$Seconds = 180) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try { if (& $Condition) { Write-Host "PASS: $Description"; return } } catch { }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Description. Inspect docker compose logs."
}
function Get-Code([string]$Url, [string]$Method = 'GET') {
    $code = & curl.exe --silent --show-error --max-time 60 --output NUL --write-out '%{http_code}' -X $Method $Url
    if ($LASTEXITCODE -ne 0) { throw "HTTP request failed: $Method $Url" }
    return [int]$code
}
function Invoke-DockerCommand([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Arguments -join ' ')" }
}
function Assert-Download {
    Invoke-WebRequest -UseBasicParsing -Uri "$ControllerUrl/api/v1/objects/$objectId" -OutFile $downloadPath -TimeoutSec 120
    Assert-Condition ((Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash -eq $originalHash) 'Downloaded bytes differ from original'
}
function Save-Status([string]$Name) {
    Get-Cluster | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $demoDirectory "$Name.json") -Encoding UTF8
}

Wait-Until { @((Get-Cluster).workers | Where-Object status -eq 'ALIVE').Count -eq 4 } 'All four workers registered'
$demoDirectory = Join-Path $PWD.Path ('data/demo-B-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $demoDirectory -Force | Out-Null
$sourcePath = Join-Path $demoDirectory 'original.bin'
$downloadPath = Join-Path $demoDirectory 'downloaded.bin'
$metadataPath = Join-Path $demoDirectory 'upload-metadata.json'
[IO.File]::WriteAllText($metadataPath, '{"course":"cloud","phase":"initial"}', (New-Object Text.UTF8Encoding $false))
$bytes = New-Object byte[] (3 * 1024 * 1024 + 17)
$random = New-Object System.Random 42
$random.NextBytes($bytes)
[IO.File]::WriteAllBytes($sourcePath, $bytes)
$originalHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash

$uploadJson = & curl.exe --silent --show-error --fail --max-time 120 `
    -F "file=@$sourcePath;type=application/octet-stream" `
    -F "metadata=<$metadataPath;type=application/json" "$ControllerUrl/api/v1/objects"
if ($LASTEXITCODE -ne 0) { throw 'Upload failed' }
$uploaded = ($uploadJson -join "`n") | ConvertFrom-Json
$objectId = $uploaded.id
Assert-Condition (-not [string]::IsNullOrWhiteSpace($objectId)) 'Upload returned no object ID'
Assert-Condition ($uploaded.chunkCount -eq 4) 'Expected four chunks for a 3 MiB + 17 byte object'
Assert-Download
Write-Host "PASS: binary upload/download, object $objectId"

$metadata = Invoke-RestMethod "$ControllerUrl/api/v1/objects/$objectId/metadata"
Assert-Condition ($metadata.customMetadata.course -eq 'cloud') 'Upload metadata was not preserved'
$patched = Invoke-RestMethod -Method Patch -Uri "$ControllerUrl/api/v1/objects/$objectId/metadata" `
    -ContentType 'application/json' -Body '{"phase":"updated","owner":"person-b"}'
Assert-Condition ($patched.customMetadata.phase -eq 'updated') 'Metadata PATCH failed'
Assert-Condition ($patched.checksum -eq $metadata.checksum) 'Metadata PATCH changed the object checksum'
Assert-Download
Write-Host 'PASS: metadata updated without re-uploading data'

Wait-Until {
    $current = @((Get-Cluster).chunks | Where-Object objectId -eq $objectId)
    $current.Count -eq 4 -and @($current | Where-Object healthyCopies -ne 2).Count -eq 0
} 'Every chunk has two usable copies'
Save-Status 'before-failure'
$first = @((Get-Cluster).chunks | Where-Object objectId -eq $objectId)[0]
$stoppedWorker = @($first.replicas | Where-Object usable)[0].workerId
Assert-Condition ($stoppedWorker -match '^worker[1-4]$') 'Unexpected worker identity'
Write-Host "Stopping a worker that actually holds this object: $stoppedWorker"
Invoke-DockerCommand @('stop', $stoppedWorker)
try {
    Wait-Until { @((Get-Cluster).workers | Where-Object { $_.workerId -eq $stoppedWorker -and $_.status -eq 'DEAD' }).Count -eq 1 } 'Heartbeat timeout detects stopped worker'
    Assert-Download
    Wait-Until {
        $current = @((Get-Cluster).chunks | Where-Object objectId -eq $objectId)
        $current.Count -eq 4 -and @($current | Where-Object healthyCopies -lt 2).Count -eq 0
    } 'Replication restored while worker is stopped'
    Save-Status 'during-failure'
} finally {
    Invoke-DockerCommand @('start', $stoppedWorker)
}
Wait-Until { @((Get-Cluster).workers | Where-Object status -eq 'ALIVE').Count -eq 4 } 'Restarted worker reconciled'
Wait-Until {
    $current = @((Get-Cluster).chunks | Where-Object objectId -eq $objectId)
    $current.Count -eq 4 -and @($current | Where-Object healthyCopies -ne 2).Count -eq 0
} 'Excess copies reduced to two after recovery'
Assert-Download
Save-Status 'after-restart'

# Corrupt only a chunk of the new disposable object created by this script.
$first = @((Get-Cluster).chunks | Where-Object objectId -eq $objectId)[0]
$chunkId = $first.chunkId
$badWorker = @($first.replicas | Where-Object usable)[0].workerId
Assert-Condition ($chunkId -match '^[0-9a-fA-F-]{36}$' -and $badWorker -match '^worker[1-4]$') 'Invalid corruption target'
Write-Host "Intentionally corrupting chunk $chunkId on $badWorker"
Invoke-DockerCommand @('exec', $badWorker, 'sh', '-c', 'printf damaged > "$1"', '--', "/data/chunks/$chunkId.chunk")
$badPort = 8081 + [int]$badWorker.Substring(6)
$chunkCode = Get-Code "http://localhost:$badPort/internal/chunks/$chunkId"
Assert-Condition ($chunkCode -eq 409 -or $chunkCode -eq 200) "Unexpected corruption check status: $chunkCode"
Write-Host "Worker GET returned $chunkCode (409 = detected; 200 can mean background repair already finished)."
Assert-Download
Wait-Until {
    $current = @((Get-Cluster).chunks | Where-Object objectId -eq $objectId)
    $current.Count -eq 4 -and @($current | Where-Object healthyCopies -ne 2).Count -eq 0 -and
        (Get-Code "http://localhost:$badPort/internal/chunks/$chunkId") -eq 200
} 'Corrupted copy repaired and replication restored'
Assert-Download
Save-Status 'after-corruption-repair'
& docker logs --since 5m controller 2>&1 | Out-File -LiteralPath (Join-Path $demoDirectory 'controller.log') -Encoding UTF8

# Verify healthy physical inventories, not only controller location records.
$counts = @{}
for ($i = 1; $i -le 4; $i++) {
    $port = 8081 + $i
    # Assign first: Invoke-RestMethod emits a JSON array as one pipeline object.
    $inventory = Invoke-RestMethod "http://localhost:$port/internal/workers/worker$i/chunks"
    foreach ($entry in $inventory) {
        if (-not $counts.ContainsKey($entry.chunkId)) { $counts[$entry.chunkId] = 0 }
        $counts[$entry.chunkId]++
    }
}
foreach ($entry in $metadata.chunks) { Assert-Condition ($counts[$entry.chunkId] -eq 2) "Physical copy count is not two for $($entry.chunkId)" }
Write-Host 'PASS: physical inventories agree with replication factor two'

# Controller restart demonstrates durable metadata and worker re-registration.
Invoke-DockerCommand @('restart', 'controller')
Wait-Until { @((Get-Cluster).workers | Where-Object status -eq 'ALIVE').Count -eq 4 } 'Controller restart and worker re-registration'
Assert-Download

# Deletion while one holder is offline must remain pending, then finish on return.
$first = @((Get-Cluster).chunks | Where-Object objectId -eq $objectId)[0]
$stoppedWorker = @($first.replicas | Where-Object usable)[0].workerId
Invoke-DockerCommand @('stop', $stoppedWorker)
try {
    Wait-Until { @((Get-Cluster).workers | Where-Object { $_.workerId -eq $stoppedWorker -and $_.status -eq 'DEAD' }).Count -eq 1 } 'Offline worker detected before deletion'
    Assert-Condition ((Get-Code "$ControllerUrl/api/v1/objects/$objectId" 'DELETE') -eq 503) 'Expected pending deletion to return 503'
    Assert-Condition ((Get-Code "$ControllerUrl/api/v1/objects/$objectId") -eq 404) 'Deleting object should not be downloadable'
    $deleting = Invoke-RestMethod "$ControllerUrl/api/v1/objects/$objectId/metadata"
    Assert-Condition ($deleting.status -eq 'DELETING') 'Pending deletion state was not retained'
    Assert-Condition ((Get-Code "$ControllerUrl/api/v1/objects/$objectId" 'DELETE') -eq 503) 'Repeated DELETE must stay pending while a holder is offline'
    Save-Status 'deletion-before-controller-restart'

    # Restart with outstanding cleanup, while the same holder remains stopped.
    Invoke-DockerCommand @('restart', 'controller')
    Wait-Until { @((Get-Cluster).workers | Where-Object status -eq 'ALIVE').Count -eq 3 } 'Controller restarted with one worker still offline'
    $deleting = Invoke-RestMethod "$ControllerUrl/api/v1/objects/$objectId/metadata"
    Assert-Condition ($deleting.status -eq 'DELETING') 'Controller restart lost pending deletion'
    Assert-Condition ((Get-Code "$ControllerUrl/api/v1/objects/$objectId") -eq 404) 'Controller restart made a deleting object downloadable'
    Assert-Condition ((Get-Code "$ControllerUrl/api/v1/objects/$objectId" 'DELETE') -eq 503) 'Deletion incorrectly completed while a holder was still offline'
    Save-Status 'deletion-after-controller-restart'
    Write-Host 'PASS: repeated DELETE and controller restart preserve pending cleanup'
} finally {
    Invoke-DockerCommand @('start', $stoppedWorker)
}
Wait-Until { (Get-Code "$ControllerUrl/api/v1/objects/$objectId/metadata") -eq 404 } 'Pending deletion completed after worker returned' 420
Wait-Until { @((Get-Cluster).workers | Where-Object status -eq 'ALIVE').Count -eq 4 } 'All four workers available after deletion recovery'
foreach ($entry in $metadata.chunks) {
    for ($i = 1; $i -le 4; $i++) {
        Invoke-DockerCommand @('exec', "worker$i", 'test', '!', '-e', "/data/chunks/$($entry.chunkId).chunk")
    }
}
Assert-Condition ((Get-Code "$ControllerUrl/api/v1/objects/$objectId" 'DELETE') -eq 404) 'Repeated object DELETE must return 404'
Write-Host "PASS: required demonstrations and offline deletion. Evidence: $demoDirectory"
