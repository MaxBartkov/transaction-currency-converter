param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$TransactionId
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(10)

function Assert-Status($Response, [int]$Expected) {
    if ([int]$Response.StatusCode -ne $Expected) {
        $body = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        throw "Expected HTTP $Expected, got $([int]$Response.StatusCode): $body"
    }
}

try {
    $ready = $client.GetAsync("$BaseUrl/actuator/health/readiness").GetAwaiter().GetResult()
    Assert-Status $ready 200

    if (!$TransactionId) {
        $body = [System.Net.Http.StringContent]::new(
            '{"description":"Smoke test","transactionDate":"2024-06-30","amountUsd":10.005}',
            [System.Text.Encoding]::UTF8, 'application/json')
        $created = $client.PostAsync("$BaseUrl/api/v1/transactions", $body).GetAwaiter().GetResult()
        Assert-Status $created 201
        $transaction = $created.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $TransactionId = $transaction.id
        if (!$TransactionId) { throw 'Creation response did not contain an id' }
        [void][Guid]::Parse($TransactionId)
    }

    $loaded = $client.GetAsync("$BaseUrl/api/v1/transactions/$TransactionId").GetAwaiter().GetResult()
    Assert-Status $loaded 200
    $transaction = $loaded.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ($transaction.id -ne $TransactionId -or $transaction.description -ne 'Smoke test' -or
        $transaction.amountUsd -ne 10.01 -or [string]$transaction.transactionDate -ne '2024-06-30') {
        throw 'Stored transaction does not match the submitted and rounded values'
    }

    $invalidBody = [System.Net.Http.StringContent]::new(
        '{"description":123,"transactionDate":"2024-06-30","amountUsd":10.005}',
        [System.Text.Encoding]::UTF8, 'application/json')
    $invalid = $client.PostAsync("$BaseUrl/api/v1/transactions", $invalidBody).GetAwaiter().GetResult()
    Assert-Status $invalid 400
    $problem = $invalid.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ($problem.code -ne 'INVALID_REQUEST') { throw 'Unexpected validation error code' }

    # Return only the id so callers can verify the same transaction after a restart.
    Write-Output $TransactionId
} finally {
    $client.Dispose()
}
