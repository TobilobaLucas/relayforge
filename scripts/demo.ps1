param(
    [string]$ApiUrl = 'http://localhost:8080',
    [string]$ReceiverUrl = 'http://localhost:9000',
    [string]$ReceiverEndpoint = 'http://receiver:9000'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envValues = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.env'))) {
    if ($line -and -not $line.StartsWith('#')) {
        $parts = $line.Split('=', 2)
        $envValues[$parts[0]] = $parts[1]
    }
}
function Invoke-Api {
    param([string]$Method, [string]$Url, [string]$Token, $Body, [string]$Key)
    $headers = @{}
    if ($Token) { $headers['Authorization'] = 'Bearer ' + $Token }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $argsForRequest = @{ Method = $Method; Uri = $Url; Headers = $headers; TimeoutSec = 15 }
    if ($null -ne $Body) { $argsForRequest['Body'] = ConvertTo-Json $Body -Depth 20 -Compress; $argsForRequest['ContentType'] = 'application/json' }
    Invoke-RestMethod @argsForRequest
}
function Wait-Delivery {
    param([string]$Token, [string]$EventId, [string]$Expected)
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        $page = Invoke-Api -Method GET -Url "$ApiUrl/api/v1/deliveries?eventId=$EventId" -Token $Token
        foreach ($delivery in $page.items) { if ($delivery.status -eq $Expected) { return $delivery } }
        Start-Sleep -Milliseconds 250
    }
    throw "Delivery did not reach $Expected within 60 seconds."
}
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$project = Invoke-Api -Method POST -Url "$ApiUrl/api/v1/admin/projects" -Token $envValues['RELAY_ADMIN_TOKEN'] -Body @{name = "Demo $runId"}
$key = $project.apiKey
Write-Output 'Created a project with an isolated API key.'
foreach ($mode in @('flaky', 'reject')) {
    $path = "/hooks/$mode-$runId"
    $endpoint = Invoke-Api -Method POST -Url "$ApiUrl/api/v1/endpoints" -Token $key -Body @{
        url = $ReceiverEndpoint + $path; description = "$mode receiver"; eventTypes = @("demo.$mode")
    }
    Invoke-Api -Method POST -Url "$ReceiverUrl/configure" -Body @{path = $path; secret = $endpoint.signingSecret; mode = $mode} | Out-Null
    $eventBody = @{type = "demo.$mode"; data = @{message = 'Hello from RelayForge'; run = $runId}}
    $event = Invoke-Api -Method POST -Url "$ApiUrl/api/v1/events" -Token $key -Body $eventBody -Key "demo-$mode"
    $duplicate = Invoke-Api -Method POST -Url "$ApiUrl/api/v1/events" -Token $key -Body $eventBody -Key "demo-$mode"
    if (-not $duplicate.duplicate -or $duplicate.eventId -ne $event.eventId) { throw 'Idempotency check failed.' }
    Write-Output "${mode}: repeated submission returned the same event."
    $expected = if ($mode -eq 'flaky') { 'SUCCEEDED' } else { 'DEAD' }
    $delivery = Wait-Delivery -Token $key -EventId $event.eventId -Expected $expected
    if ($mode -eq 'flaky') {
        if ($delivery.attemptCount -ne 3) { throw 'Expected exactly three attempts.' }
        Write-Output 'flaky: receiver failed twice, then succeeded on attempt 3.'
    } else {
        if ($delivery.attemptCount -ne 1) { throw 'Permanent failure should stop after one attempt.' }
        Invoke-Api -Method POST -Url "$ReceiverUrl/configure" -Body @{path = $path; secret = $endpoint.signingSecret; mode = 'ok'} | Out-Null
        Invoke-Api -Method POST -Url "$ApiUrl/api/v1/deliveries/$($delivery.id)/replay" -Token $key | Out-Null
        $recovered = Wait-Delivery -Token $key -EventId $event.eventId -Expected SUCCEEDED
        if ($recovered.replayCount -ne 1 -or $recovered.attemptCount -ne 2) { throw 'Replay history check failed.' }
        Write-Output 'reject: permanent failure entered dead letters; replay succeeded after fixing the receiver.'
    }
    $attempts = Invoke-Api -Method GET -Url "$ApiUrl/api/v1/deliveries/$($delivery.id)/attempts" -Token $key
    Write-Output ('  history: ' + (($attempts | ForEach-Object { "$($_.attemptNumber):$($_.outcome)" }) -join ', '))
}
Write-Output 'Demo passed. The receiver log shows signature verification for every attempt.'
