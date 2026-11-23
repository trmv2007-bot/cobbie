<#
  Clears the way for ./gradlew runClientGameTest, or stops it with a sentence that says why.

  A client game test boots a second real Minecraft on the same 8 GB card a live client is already
  using. When there is not enough VRAM, the failure lands mid-allocation ("could not create a
  texture"), which reads like a bug in Cobbie's renderer and is not one. This runs first and turns
  that into either a working run or a refusal that names the cause.

  Three checks, in the order that makes the numbers meaningful:
    1. Refuse while another Minecraft client is running (the dev client or the real game).
    2. Ask Ollama to unload whatever models it is holding on the GPU. lfm2.5:8b at 128k context
       sits on ~6.2 GB of an 8 GB card, which is most of it.
    3. Refuse unless the card now has enough free VRAM for a client plus its texture atlases.

  Exit 0 = go. Exit 1 = do not run, the message says what to fix.
#>

param(
	[string] $ApiBase = "http://127.0.0.1:11434",
	[int] $MinFreeMiB = 2500
)

$ErrorActionPreference = "Stop"

function Fail([string] $message) {
	Write-Host "[preflight] REFUSED: $message"
	exit 1
}

# 1. A live client owns the VRAM we are about to ask for, and it is his session, not ours to close.
$running = @(Get-CimInstance Win32_Process |
	Where-Object {
		$_.CommandLine -and (
			$_.CommandLine -match 'net\.minecraft\.client\.main\.Main' -or
			$_.Name -match '^(Minecraft|TLauncher)'
		) -and $_.CommandLine -notmatch 'gametest'
	})

if ($running.Count -gt 0) {
	$what = ($running | ForEach-Object { "$($_.Name) (pid $($_.ProcessId))" }) -join ", "
	Fail "Minecraft is already running: $what. A client game test boots a second client on the same GPU and the two will fight over VRAM. Close the game, then run this again."
}

# 2. Ollama holds the model resident between requests. Unloading is a normal client operation and
#    the next chat simply reloads it; leaving it resident costs the whole test run.
$freed = 0
try {
	$residents = @(Invoke-RestMethod -Uri "$ApiBase/api/ps" -TimeoutSec 3 -ErrorAction Stop).models
	foreach ($model in $residents) {
		$freed++
		Write-Host "[preflight] unloading $($model.name) ($([math]::Round($model.size_vram / 1MB)) MiB on GPU)"
		$body = @{ model = $model.name; keep_alive = 0 } | ConvertTo-Json -Compress
		Invoke-RestMethod -Method Post -Uri "$ApiBase/api/generate" -Body $body -ContentType "application/json" -TimeoutSec 30 | Out-Null
	}
	if ($freed -eq 0) { Write-Host "[preflight] no model resident on the GPU" }
} catch {
	Write-Host "[preflight] no Ollama answering at $ApiBase - nothing to unload ($($_.Exception.Message))"
}

# 3. Headroom, measured rather than assumed.
$smi = (Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue).Source
if (-not $smi) { $smi = "C:\Windows\System32\nvidia-smi.exe" }
if (Test-Path $smi) {
	# The unload is asynchronous on Ollama's side; the card releases within a second or two.
	Start-Sleep -Milliseconds 1500
	$free = [int]((& $smi --query-gpu=memory.free --format=csv,noheader,nounits) -replace '[^\d]', '')
	$total = [int]((& $smi --query-gpu=memory.total --format=csv,noheader,nounits) -replace '[^\d]', '')
	$name = (& $smi --query-gpu=name --format=csv,noheader) -join ""
	Write-Host "[preflight] ${name}: $free MiB free of $total MiB (need $MinFreeMiB)"
	if ($free -lt $MinFreeMiB) {
		Fail "$free MiB of VRAM free on $name, and a client game test needs about $MinFreeMiB. Something else is holding the card - close it, or lower the bar with -MinFreeMiB."
	}
} else {
	Write-Host "[preflight] no nvidia-smi on this machine - skipping the VRAM check"
}

Write-Host "[preflight] ok"
exit 0
