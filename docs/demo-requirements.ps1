<#
Requirements demonstration for the local Compose project.
Run from any directory: .\docs\demo-requirements.ps1 [-Interactive]
Only newly generated object/chunk IDs are deliberately corrupted or deleted.
Project containers are temporarily stopped/restarted; persistent volumes are retained.
#>
[CmdletBinding()]
param(
    [switch]$Interactive,
    [string]$AdditionalFile,
    [string]$AdditionalContentType = 'application/octet-stream'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$controller = 'http://localhost:8081'
$workerIds = @('worker1', 'worker2', 'worker3', 'worker4')
$chunkSize = 1048576
$runId = [guid]::NewGuid().ToString()
$evidence = Join-Path $project "data/requirements-demo-$runId"
$checks = New-Object 'System.Collections.Generic.List[object]'
$requests = New-Object 'System.Collections.Generic.List[object]'
$stopped = New-Object 'System.Collections.Generic.HashSet[string]'
$controllerPaused = $false
$requestNumber = 0
$objectId = $null
$probeId = $null
$succeeded = $false
$transcriptStarted = $false
$utf8 = New-Object System.Text.UTF8Encoding $false

function Save-Json($Value, [string]$Name) {
    [IO.File]::WriteAllText((Join-Path $evidence $Name), ($Value | ConvertTo-Json -Depth 25), $utf8)
}
function Check([bool]$Condition, [string]$Description) {
    $checks.Add([pscustomobject]@{ time = [DateTime]::UtcNow.ToString('o'); passed = $Condition; check = $Description })
    if (-not $Condition) { throw "FAIL: $Description" }
    Write-Host "PASS: $Description"
}
function Phase([int]$Number, [string]$Title, [string]$Purpose) {
    Write-Host "`n========== STAGE $Number : $Title ==========" -ForegroundColor Cyan
    Write-Host $Purpose
    if ($Interactive) { $null = Read-Host 'Press Enter to run this stage' }
}
function Docker([string[]]$Arguments) {
    # Native stderr is not itself a failed command (Docker writes progress/logs there).
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & docker.exe @Arguments 2>&1
        $nativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($nativeExit -ne 0) { throw "docker $($Arguments -join ' ') failed: $($output -join ' ')" }
    return ($output -join "`n")
}
function Worker-Url([string]$WorkerId) {
    if ($WorkerId -notmatch '^worker[1-4]$') { throw "Unexpected worker ID: $WorkerId" }
    return "http://localhost:$(8081 + [int]$WorkerId.Substring(6))"
}
function Request([string]$Name, [string]$Method, [string]$Url, [string[]]$Extra = @()) {
    $script:requestNumber++
    $prefix = '{0:D3}-{1}' -f $script:requestNumber, $Name
    $body = Join-Path $evidence "$prefix.body"
    $headers = Join-Path $evidence "$prefix.headers.txt"
    $curlArguments = @('--silent', '--show-error', '--connect-timeout', '3', '--max-time', '120',
        '--request', $Method, '--dump-header', $headers, '--output', $body, '--write-out', '%{http_code}')
    $code = & curl.exe @curlArguments @Extra $Url
    if ($LASTEXITCODE -ne 0) { throw "Transport failed: $Method $Url (curl exit $LASTEXITCODE)" }
    $response = [pscustomobject]@{ name = $Name; method = $Method; url = $Url; status = [int]($code -join ''); body = $body; headers = $headers }
    $requests.Add($response)
    Write-Host "$Method $Url -> $($response.status)"
    return $response
}
function Json($Response) { return (Get-Content -LiteralPath $Response.body -Raw | ConvertFrom-Json) }
function Timestamp($Value) {
    # PowerShell 7 may deserialize ISO timestamps to DateTime; Windows PowerShell
    # can leave strings. Do not round-trip through a culture-dependent string.
    if ($Value -is [DateTimeOffset]) { return $Value }
    if ($Value -is [DateTime]) { return [DateTimeOffset]$Value }
    return [DateTimeOffset]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal)
}
function Hash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Bytes-Hash([byte[]]$Bytes) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}
function Error-Response($Response, [int]$Code) {
    Check ($Response.status -eq $Code) "$($Response.name): HTTP $Code"
    $body = Json $Response
    Check ($body.status -eq $Code -and -not [string]::IsNullOrWhiteSpace($body.message)) "$($Response.name): meaningful JSON error"
}
function Cluster { return (Invoke-RestMethod "$controller/internal/status" -TimeoutSec 10) }
function Wait-For([scriptblock]$Condition, [string]$Description, [int]$Seconds = 180) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    $lastError = ''
    do {
        try { if (& $Condition) { Check $true $Description; return } }
        catch { $lastError = $_.Exception.Message }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Description. Last transient error: $lastError"
}
function Four-Alive {
    $workers = @((Cluster).workers)
    return ($workers.Count -eq 4 -and @($workers | Where-Object status -eq 'ALIVE').Count -eq 4)
}
function Snapshot([string]$Name) {
    $state = Cluster
    Save-Json $state "$Name.json"
    $state.workers | Select-Object workerId, status, lastHeartbeat, chunkCount | Format-Table -AutoSize | Out-Host
    @($state.chunks | Where-Object objectId -eq $script:objectId) | ForEach-Object {
        [pscustomobject]@{
            index = $_.chunkIndex; chunkId = $_.chunkId; usableCopies = $_.healthyCopies
            locations = (($_.replicas | ForEach-Object { "$($_.workerId):$($_.state):usable=$($_.usable)" }) -join ', ')
        }
    } | Format-Table -AutoSize -Wrap | Out-Host
    return $state
}
function Two-Copies {
    $entries = @((Cluster).chunks | Where-Object objectId -eq $script:objectId)
    return ($entries.Count -eq 4 -and @($entries | Where-Object healthyCopies -ne 2).Count -eq 0)
}
function Download([string]$Id, [string]$Original, [string]$Type, [string]$Name) {
    $response = Request $Name 'GET' "$controller/api/v1/objects/$Id"
    Check ($response.status -eq 200) "$Name`: download returns 200"
    Check ((Hash $response.body) -eq (Hash $Original)) "$Name`: downloaded SHA-256 equals original"
    $headers = Get-Content -LiteralPath $response.headers -Raw
    Check ($headers -match ('(?im)^Content-Type:\s*' + [regex]::Escape($Type) + '\s*\r?$')) "$Name`: correct binary Content-Type"
    Check ((Get-Item -LiteralPath $response.body).Length -eq (Get-Item -LiteralPath $Original).Length) "$Name`: original byte length preserved"
}
function List-Contains([string]$Id) {
    for ($page = 0; ; $page++) {
        $response = Request "list-page-$page" 'GET' "$controller/api/v1/objects?page=$page&size=100"
        Check ($response.status -eq 200) "List page $page returns 200"
        $listing = Json $response
        $found = @($listing.items | Where-Object id -eq $Id)
        if ($found.Count -eq 1) {
            foreach ($field in @('id', 'name', 'size', 'contentType')) {
                Check ($found[0].PSObject.Properties.Name -contains $field) "List entry includes $field"
            }
            return $true
        }
        if (($page + 1) * 100 -ge $listing.total -or @($listing.items).Count -eq 0) { return $false }
    }
}
function Physical-Copies([string]$Name) {
    $locations = @{}
    foreach ($worker in $workerIds) {
        $response = Request "$Name-$worker-inventory" 'GET' "$(Worker-Url $worker)/internal/workers/$worker/chunks"
        Check ($response.status -eq 200) "$worker inventory returns 200"
        $inventory = Json $response
        foreach ($entry in $inventory) {
            if (-not $locations.ContainsKey([string]$entry.chunkId)) { $locations[[string]$entry.chunkId] = @() }
            $locations[[string]$entry.chunkId] += $worker
        }
    }
    $rows = foreach ($chunk in $script:metadata.chunks) {
        $holders = @($locations[[string]$chunk.chunkId])
        Check ($holders.Count -eq 2 -and @($holders | Sort-Object -Unique).Count -eq 2) "$($chunk.chunkId): exactly two distinct physical inventory locations"
        foreach ($worker in $holders) {
            $response = Request "$Name-$worker-$($chunk.index)" 'GET' "$(Worker-Url $worker)/internal/chunks/$($chunk.chunkId)"
            Check ($response.status -eq 200 -and (Hash $response.body) -eq $chunk.checksum) "$worker chunk $($chunk.index): physical bytes verify"
        }
        [pscustomobject]@{ index = $chunk.index; chunkId = $chunk.chunkId; size = $chunk.size; replica1Worker = $holders[0]; replica2Worker = $holders[1] }
    }
    $rows | Format-Table -AutoSize | Out-Host
    Save-Json @($rows) "$Name-physical-locations.json"
    $used = @($rows | ForEach-Object { $_.replica1Worker; $_.replica2Worker } | Sort-Object -Unique)
    Write-Host "This object's chunks are distributed over: $($used -join ', '). Placement uses current worker load; exact locations can vary."
}
function Assert-Files-Removed($Chunks) {
    foreach ($chunk in $Chunks) {
        if ($chunk.chunkId -notmatch '^[0-9a-fA-F-]{36}$') { throw 'Invalid chunk path' }
        foreach ($worker in $workerIds) {
            $null = Docker @('exec', $worker, 'test', '!', '-e', "/data/chunks/$($chunk.chunkId).chunk")
        }
    }
    Check $true 'Associated chunk files are absent on all four workers'
}

Push-Location $project
try {
    New-Item -ItemType Directory -Path $evidence -Force | Out-Null
    $null = Start-Transcript -LiteralPath (Join-Path $evidence 'transcript.txt')
    $transcriptStarted = $true
    Write-Host "Evidence directory: $evidence"
    Phase 1 'Deployment and readiness' 'PDF sections 2 and 9: one controller, four workers, Docker, persistent storage. This PowerShell process is the client.'
    $null = Get-Command docker.exe -ErrorAction Stop
    $null = Get-Command curl.exe -ErrorAction Stop
    $null = Docker @('compose', 'config', '--quiet')
    $containers = (Docker (@('inspect', 'controller') + $workerIds)) | ConvertFrom-Json
    Check (@($containers).Count -eq 5) 'Five project containers exist'
    $mounts = foreach ($container in $containers) {
        $name = $container.Name.TrimStart('/')
        Check ($container.Config.Labels.'com.docker.compose.project' -eq 'mini-object-store') "$name belongs to this Compose project"
        Check ($container.State.Running -and -not $container.State.Paused) "$name is running and unpaused"
        $mount = @($container.Mounts | Where-Object { $_.Destination -eq '/data' -and $_.Type -eq 'volume' -and $_.RW })
        Check ($mount.Count -eq 1) "$name has a writable persistent /data volume"
        [pscustomobject]@{ container = $name; volume = $mount[0].Name; destination = '/data'; image = $container.Config.Image }
    }
    Check (@($mounts.volume | Sort-Object -Unique).Count -eq 5) 'Controller and workers use separate volumes'
    $mounts | Format-Table -AutoSize | Out-Host
    Save-Json @($mounts) 'deployment-volumes.json'
    Wait-For { Four-Alive } 'All four workers have registered and are ALIVE'
    $baseline = Cluster
    Save-Json $baseline 'initial-cluster.json'
    $heartbeatBefore = @{}
    foreach ($worker in $baseline.workers) { $heartbeatBefore[$worker.workerId] = Timestamp $worker.lastHeartbeat }
    Wait-For {
        $fresh = @((Cluster).workers)
        @($fresh | Where-Object { (Timestamp $_.lastHeartbeat) -gt $heartbeatBefore[$_.workerId] }).Count -eq 4
    } 'Heartbeats advance for all four workers' 40

    Phase 2 'Internal chunk APIs and persistent worker storage' 'PDF section 6: PUT, GET, inventory and DELETE. A new isolated chunk tests bytes surviving a worker restart.'
    $probeId = [guid]::NewGuid().ToString()
    $probePath = Join-Path $evidence 'worker-probe.bin'
    [IO.File]::WriteAllBytes($probePath, [byte[]](0..255))
    $probeUrl = "$(Worker-Url 'worker1')/internal/chunks/$probeId"
    $putArgs = @('-H', 'Content-Type: application/octet-stream', '-H', "X-Checksum: $(Hash $probePath)", '--data-binary', "@$probePath")
    $response = Request 'probe-put' 'PUT' $probeUrl $putArgs
    Check ($response.status -eq 201) 'Worker PUT creates a new chunk with 201'
    $response = Request 'probe-retry' 'PUT' $probeUrl $putArgs
    Check ($response.status -eq 204) 'Identical worker PUT is idempotent: 204'
    $response = Request 'probe-get-before' 'GET' $probeUrl
    Check ($response.status -eq 200 -and (Hash $response.body) -eq (Hash $probePath)) 'Worker GET returns the original bytes'
    $null = $stopped.Add('worker1')
    $probeRestartAfter = [DateTimeOffset]::UtcNow
    $null = Docker @('stop', 'worker1')
    $null = Docker @('start', 'worker1')
    $null = $stopped.Remove('worker1')
    Wait-For {
        $health = Invoke-RestMethod "$(Worker-Url 'worker1')/internal/health" -TimeoutSec 5
        $current = @((Cluster).workers | Where-Object workerId -eq 'worker1')[0]
        $health.status -eq 'UP' -and $current.status -eq 'ALIVE' -and (Timestamp $current.lastHeartbeat) -gt $probeRestartAfter
    } 'Worker re-registers after restart'
    $response = Request 'probe-get-after-restart' 'GET' $probeUrl
    Check ($response.status -eq 200 -and (Hash $response.body) -eq (Hash $probePath)) 'Persistent worker bytes survived stop/start'
    $response = Request 'probe-inventory' 'GET' "$(Worker-Url 'worker1')/internal/workers/worker1/chunks"
    Check ($response.status -eq 200 -and @((Json $response) | Where-Object chunkId -eq $probeId).Count -eq 1) 'Worker inventory reports the stored probe'
    $conflictPath = Join-Path $evidence 'probe-conflict.bin'
    [IO.File]::WriteAllBytes($conflictPath, [byte[]](255..0))
    Error-Response (Request 'probe-conflict' 'PUT' $probeUrl @('-H', 'Content-Type: application/octet-stream', '-H', "X-Checksum: $(Hash $conflictPath)", '--data-binary', "@$conflictPath")) 409
    Check ((Request 'probe-delete' 'DELETE' $probeUrl).status -eq 204) 'Worker DELETE confirms removal with 204'
    Check ((Request 'probe-delete-retry' 'DELETE' $probeUrl).status -eq 204) 'Repeated worker DELETE is idempotent'
    Error-Response (Request 'probe-get-deleted' 'GET' $probeUrl) 404
    Error-Response (Request 'probe-late-put' 'PUT' $probeUrl $putArgs) 409
    $probeId = $null

    Phase 3 'Object upload, download and metadata' 'PDF sections 1, 3, 5.1-5.3 and 10: upload a 3 MiB + 17 byte binary object with custom metadata.'
    $source = Join-Path $evidence 'original.bin'
    $bytes = New-Object byte[] (3 * $chunkSize + 17)
    $random = New-Object System.Random 42
    $random.NextBytes($bytes)
    [IO.File]::WriteAllBytes($source, $bytes)
    $metadataFile = Join-Path $evidence 'upload-custom-metadata.json'
    [IO.File]::WriteAllText($metadataFile, '{"course":"cloud","phase":"initial","owner":"person-b"}', $utf8)
    $response = Request 'object-upload' 'POST' "$controller/api/v1/objects" @('-F', "file=@$source;type=application/octet-stream", '-F', "metadata=<$metadataFile;type=application/json")
    Check ($response.status -eq 201) 'Object upload returns 201 Created'
    $uploaded = Json $response
    $objectId = [string]$uploaded.id
    Check ($objectId -match '^[0-9a-fA-F-]{36}$') 'Upload supplies an object ID'
    Save-Json @{ objectId = $objectId; source = $source; checksum = (Hash $source) } 'test-object.json'
    Download $objectId $source 'application/octet-stream' 'initial-download'
    $response = Request 'metadata-before' 'GET' "$controller/api/v1/objects/$objectId/metadata"
    Check ($response.status -eq 200) 'Metadata GET returns 200'
    $metadata = Json $response
    foreach ($field in @('id', 'name', 'size', 'contentType', 'createdAt', 'modifiedAt', 'checksum', 'chunkCount', 'chunks', 'customMetadata')) {
        Check ($metadata.PSObject.Properties.Name -contains $field) "Metadata contains $field"
    }
    Check ($metadata.name -eq 'original.bin' -and $metadata.size -eq $bytes.Length) 'Metadata name and size match the upload'
    Check ($metadata.contentType -eq 'application/octet-stream' -and $metadata.checksum -eq (Hash $source)) 'Metadata content type and checksum match'
    Check ((Timestamp $metadata.modifiedAt) -ge (Timestamp $metadata.createdAt)) 'Creation and modification timestamps are valid'
    Check ($metadata.customMetadata.course -eq 'cloud' -and $metadata.customMetadata.phase -eq 'initial') 'Upload custom metadata is preserved'

    Phase 4 'Chunk boundaries, ordering, distribution and replication' 'PDF sections 4 and 10: four ordered chunks, sizes 1048576, 1048576, 1048576 and 17, each on two different workers.'
    Check ($metadata.chunkCount -eq 4 -and @($metadata.chunks).Count -eq 4) 'Object has exactly four chunks'
    Check (@($metadata.chunks.chunkId | Sort-Object -Unique).Count -eq 4) 'Chunk IDs are distinct'
    for ($index = 0; $index -lt 4; $index++) {
        $chunk = $metadata.chunks[$index]
        $expectedSize = [Math]::Min($chunkSize, $bytes.Length - $index * $chunkSize)
        $part = New-Object byte[] $expectedSize
        [Array]::Copy($bytes, $index * $chunkSize, $part, 0, $expectedSize)
        Check ($chunk.index -eq $index -and $chunk.size -eq $expectedSize) "Chunk $index has the correct index and byte size"
        Check ($chunk.checksum -eq (Bytes-Hash $part)) "Chunk $index checksum matches its exact original byte range"
        Check (@($chunk.replicas | Where-Object state -eq 'OK').Count -eq 2 -and @($chunk.replicas.workerId | Sort-Object -Unique).Count -eq 2) "Chunk $index metadata lists two distinct workers"
    }
    Wait-For { Two-Copies } 'Controller reports two usable copies per chunk'
    $null = Snapshot 'before-worker-failure'
    Physical-Copies 'normal'

    Phase 5 'Custom metadata PATCH and object listing' 'PDF sections 5.4, 5.6 and 10: change metadata without uploading the file again; list the stored object.'
    $patchFile = Join-Path $evidence 'metadata-patch.json'
    [IO.File]::WriteAllText($patchFile, '{"phase":"updated","reviewer":"project-demo"}', $utf8)
    $response = Request 'metadata-patch' 'PATCH' "$controller/api/v1/objects/$objectId/metadata" @('-H', 'Content-Type: application/json', '--data-binary', "@$patchFile")
    Check ($response.status -eq 200) 'PATCH returns 200'
    $patched = Json $response
    Check ($patched.customMetadata.phase -eq 'updated' -and $patched.customMetadata.reviewer -eq 'project-demo' -and $patched.customMetadata.course -eq 'cloud') 'PATCH adds and updates keys while retaining other metadata'
    Check ($patched.checksum -eq $metadata.checksum -and $patched.size -eq $metadata.size -and ($patched.chunks.chunkId -join ',') -eq ($metadata.chunks.chunkId -join ',')) 'PATCH preserves object bytes and chunk identities'
    Check ((Timestamp $patched.createdAt) -eq (Timestamp $metadata.createdAt) -and (Timestamp $patched.modifiedAt) -ge (Timestamp $metadata.modifiedAt)) 'PATCH preserves creation time and updates modification time'
    Download $objectId $source 'application/octet-stream' 'download-after-patch'
    Check (List-Contains $objectId) 'List contains the stored object with required summary fields'

    Phase 6 'Second object, normal DELETE and API errors' 'PDF section 5: unique object IDs, optional upload metadata, DELETE 204, unknown-object 404 and invalid-request 400.'
    $smallPath = Join-Path $evidence 'small.txt'
    [IO.File]::WriteAllText($smallPath, "Small text object for the normal deletion demonstration.`n", $utf8)
    $smallResponse = Request 'small-upload' 'POST' "$controller/api/v1/objects" @('-F', "file=@$smallPath;type=text/plain")
    Check ($smallResponse.status -eq 201) 'Upload without custom metadata succeeds'
    $small = Json $smallResponse
    Check ($small.id -ne $objectId) 'Separate uploads receive distinct object IDs'
    Download $small.id $smallPath 'text/plain' 'small-download'
    $smallMeta = Json (Request 'small-metadata' 'GET' "$controller/api/v1/objects/$($small.id)/metadata")
    Check ((Request 'small-delete' 'DELETE' "$controller/api/v1/objects/$($small.id)").status -eq 204) 'Normal successful object DELETE returns 204'
    Assert-Files-Removed $smallMeta.chunks
    Error-Response (Request 'small-get-after-delete' 'GET' "$controller/api/v1/objects/$($small.id)") 404
    Error-Response (Request 'small-delete-again' 'DELETE' "$controller/api/v1/objects/$($small.id)") 404
    Check (-not (List-Contains $small.id)) 'Deleted object is absent from listing'
    $missingId = [guid]::NewGuid().ToString()
    Error-Response (Request 'unknown-metadata' 'GET' "$controller/api/v1/objects/$missingId/metadata") 404
    Error-Response (Request 'unknown-patch' 'PATCH' "$controller/api/v1/objects/$missingId/metadata" @('-H', 'Content-Type: application/json', '--data-binary', "@$patchFile")) 404
    Error-Response (Request 'invalid-pagination' 'GET' "$controller/api/v1/objects?page=abc") 400
    $badJson = Join-Path $evidence 'invalid-metadata.json'
    [IO.File]::WriteAllText($badJson, '{invalid', $utf8)
    Error-Response (Request 'invalid-upload-metadata' 'POST' "$controller/api/v1/objects" @('-F', "file=@$smallPath;type=text/plain", '-F', "metadata=<$badJson;type=application/json")) 400
    [IO.File]::WriteAllText($badJson, '[]', $utf8)
    Error-Response (Request 'invalid-patch' 'PATCH' "$controller/api/v1/objects/$objectId/metadata" @('-H', 'Content-Type: application/json', '--data-binary', "@$badJson")) 400
    if ($AdditionalFile) {
        $extraPath = (Resolve-Path -LiteralPath $AdditionalFile).Path
        Check ((Get-Item -LiteralPath $extraPath).Length -gt 0) 'Additional real file is nonempty'
        $response = Request 'additional-upload' 'POST' "$controller/api/v1/objects" @('-F', "file=@$extraPath;type=$AdditionalContentType")
        Check ($response.status -eq 201) 'Additional real file uploads with 201'
        $extra = Json $response
        Download $extra.id $extraPath $AdditionalContentType 'additional-download'
        $response = Request 'additional-delete' 'DELETE' "$controller/api/v1/objects/$($extra.id)"
        Check ($response.status -eq 204 -or $response.status -eq 503) 'Additional object deletion accepted or complete'
        Wait-For {
            try { $null = Invoke-RestMethod "$controller/api/v1/objects/$($extra.id)/metadata" -TimeoutSec 10; return $false }
            catch { return ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) }
        } 'Additional uploaded copy is deleted' 420
    }

    Phase 7 'Stop an actual holder; retrieve and repair while it is offline' 'PDF sections 7 and 10: stop a Docker worker containing this object, show detection, verify download and restored replication.'
    $state = Snapshot 'immediately-before-stop'
    $first = @($state.chunks | Where-Object objectId -eq $objectId)[0]
    $holder = @($first.replicas | Where-Object usable)[0].workerId
    Check ($workerIds -contains $holder) 'Failure target is a real holder of the new object'
    $null = $stopped.Add($holder)
    Write-Host "docker stop $holder"
    $null = Docker @('stop', $holder)
    Download $objectId $source 'application/octet-stream' 'download-during-worker-failure'
    $timeline = New-Object 'System.Collections.Generic.List[object]'
    Wait-For {
        $current = Cluster
        $timeline.Add([pscustomobject]@{ time = [DateTime]::UtcNow.ToString('o'); state = $current })
        @($current.workers | Where-Object { $_.workerId -eq $holder -and $_.status -eq 'DEAD' }).Count -eq 1
    } 'Heartbeat timeout marks the stopped worker DEAD'
    Save-Json @($timeline.ToArray()) 'failure-detection-timeline.json'
    $null = Snapshot 'worker-dead'
    Download $objectId $source 'application/octet-stream' 'download-after-failure-detection'
    Wait-For { Two-Copies } 'Replication restored to two usable copies while the worker remains stopped'
    $null = Snapshot 'repaired-while-offline'
    $null = Docker @('start', $holder)
    $null = $stopped.Remove($holder)
    Wait-For { Four-Alive } 'Returning worker registered and reconciled'
    Wait-For { Two-Copies } 'Excess copies reduced to two after worker recovery'
    Download $objectId $source 'application/octet-stream' 'download-after-worker-recovery'
    $null = Snapshot 'after-worker-recovery'
    Physical-Copies 'after-worker-recovery'

    Phase 8 'Intentional corruption, detection and repair' 'PDF sections 8 and 10: damage one replica only, observe 409, retrieve correct bytes from a valid copy, and verify repaired copies.'
    $state = Cluster
    $first = @($state.chunks | Where-Object objectId -eq $objectId)[0]
    $badWorker = @($first.replicas | Where-Object usable)[0].workerId
    $badChunk = $first.chunkId
    Check ($workerIds -contains $badWorker -and $badChunk -match '^[0-9a-fA-F-]{36}$') 'Corruption target belongs to the new demo object'
    # Briefly freeze controller repair so the 409 evidence cannot disappear before inspection.
    $controllerPaused = $true
    $null = Docker @('pause', 'controller')
    try {
        $null = Docker @('exec', $badWorker, 'sh', '-c', 'printf damaged > "$1"', '--', "/data/chunks/$badChunk.chunk")
        Error-Response (Request 'corrupted-worker-get' 'GET' "$(Worker-Url $badWorker)/internal/chunks/$badChunk") 409
    } finally {
        $null = Docker @('unpause', 'controller')
        $controllerPaused = $false
    }
    Download $objectId $source 'application/octet-stream' 'download-with-corrupted-replica'
    Wait-For {
        if (-not (Two-Copies)) { return $false }
        $inventory = Invoke-RestMethod "$(Worker-Url $badWorker)/internal/workers/$badWorker/chunks" -TimeoutSec 10
        @($inventory | Where-Object chunkId -eq $badChunk).Count -eq 1
    } 'Corrupted copy repaired and replication restored'
    Physical-Copies 'after-corruption-repair'
    $null = Snapshot 'after-corruption-repair'

    Phase 9 'Controller restart with a stored object' 'Additional reliability check: preserve controller metadata and serve the same bytes after worker re-registration.'
    $null = Docker @('restart', 'controller')
    Wait-For { Four-Alive } 'Controller restarted and all workers re-registered'
    Download $objectId $source 'application/octet-stream' 'download-after-controller-restart'
    $null = Snapshot 'after-controller-restart'

    Phase 10 'Pending deletion, restart and automatic completion' 'Additional reliability check: offline deletion returns 503; retain progress across retries and controller restart; finish after worker return.'
    $state = Cluster
    $first = @($state.chunks | Where-Object objectId -eq $objectId)[0]
    $holder = @($first.replicas | Where-Object usable)[0].workerId
    $null = $stopped.Add($holder)
    $null = Docker @('stop', $holder)
    Wait-For { @((Cluster).workers | Where-Object { $_.workerId -eq $holder -and $_.status -eq 'DEAD' }).Count -eq 1 } 'Holder is offline before deletion'
    Error-Response (Request 'offline-delete' 'DELETE' "$controller/api/v1/objects/$objectId") 503
    Error-Response (Request 'offline-delete-retry' 'DELETE' "$controller/api/v1/objects/$objectId") 503
    Error-Response (Request 'deleting-object-get' 'GET' "$controller/api/v1/objects/$objectId") 404
    $response = Request 'deleting-metadata' 'GET' "$controller/api/v1/objects/$objectId/metadata"
    Check ($response.status -eq 200 -and (Json $response).status -eq 'DELETING') 'Pending object metadata remains DELETING'
    Check (-not (List-Contains $objectId)) 'Deleting object is hidden from listing'
    Error-Response (Request 'deleting-object-patch' 'PATCH' "$controller/api/v1/objects/$objectId/metadata" @('-H', 'Content-Type: application/json', '--data-binary', "@$patchFile")) 409
    $null = Snapshot 'pending-deletion-before-restart'
    $null = Docker @('restart', 'controller')
    Wait-For { @((Cluster).workers | Where-Object status -eq 'ALIVE').Count -eq 3 } 'Controller restarted while the holder is still offline'
    $response = Request 'pending-metadata-after-restart' 'GET' "$controller/api/v1/objects/$objectId/metadata"
    Check ($response.status -eq 200 -and (Json $response).status -eq 'DELETING') 'Pending deletion survives controller restart'
    Error-Response (Request 'pending-delete-after-restart' 'DELETE' "$controller/api/v1/objects/$objectId") 503
    $null = Snapshot 'pending-deletion-after-restart'
    $null = Docker @('start', $holder)
    $null = $stopped.Remove($holder)
    # Only metadata GETs while waiting: completion must come from the background runner.
    Wait-For {
        try { $null = Invoke-RestMethod "$controller/api/v1/objects/$objectId/metadata" -TimeoutSec 10; return $false }
        catch { return ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) }
    } 'Background deletion completed after worker return without another DELETE' 420
    Wait-For { Four-Alive } 'All four workers are available after the demonstration'
    Assert-Files-Removed $metadata.chunks
    Error-Response (Request 'final-deleted-metadata' 'GET' "$controller/api/v1/objects/$objectId/metadata") 404
    Error-Response (Request 'final-delete-again' 'DELETE' "$controller/api/v1/objects/$objectId") 404
    $null = Snapshot 'final-cluster'
    $succeeded = $true
    Write-Host "`nPASS: all ten demonstration stages completed." -ForegroundColor Green
    Write-Host "Evidence: $evidence"
} catch {
    if (Test-Path -LiteralPath $evidence) {
        [IO.File]::WriteAllText((Join-Path $evidence 'failure.txt'), ($_ | Format-List * -Force | Out-String), $utf8)
    }
    throw
} finally {
    if ($controllerPaused) {
        try { $null = Docker @('unpause', 'controller') } catch { Write-Warning "Restore controller manually: docker unpause controller. $_" }
    }
    foreach ($worker in $stopped) {
        try { $null = Docker @('start', $worker) } catch { Write-Warning "Restore worker manually: docker start $worker. $_" }
    }
    if ($probeId) {
        try { $null = Request 'probe-finally-delete' 'DELETE' "$(Worker-Url 'worker1')/internal/chunks/$probeId" } catch { Write-Warning "Probe cleanup remains for $probeId. $_" }
    }
    if (Test-Path -LiteralPath $evidence) {
        Save-Json @{ passed = $succeeded; objectId = $objectId; checks = $checks.ToArray(); completedAt = [DateTime]::UtcNow.ToString('o') } 'results.json'
        $requests.ToArray() | Export-Csv -LiteralPath (Join-Path $evidence 'http-requests.csv') -NoTypeInformation -Encoding UTF8
        foreach ($container in @('controller') + $workerIds) {
            try {
                $log = Docker @('logs', '--tail', '250', $container)
                [IO.File]::WriteAllText((Join-Path $evidence "$container.log"), $log, $utf8)
            } catch { Write-Warning "Could not save $container logs: $_" }
        }
        if (-not $succeeded) { Write-Warning "Demo stopped at a failed check. Evidence retained at $evidence; demo objects may remain for diagnosis." }
    }
    if ($transcriptStarted) { $null = Stop-Transcript }
    Pop-Location
}
