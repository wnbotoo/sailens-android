# Samples the Reference app every $IntervalSec seconds for $Minutes minutes: threads, memory (PSS, graphics, GL),
# thermal status and battery temperature. One CSV line per sample.
param([int]$Minutes = 90, [int]$IntervalSec = 10, [string]$Out)
$pkg = "com.sailens.reference"
"wall,pid,threads,totalPssKb,graphicsKb,glMtrackKb,thermalStatus,batteryTempC" | Set-Content -Path $Out -Encoding utf8
$end = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $end) {
    $wall = (Get-Date).ToString("HH:mm:ss")
    $procId = (adb shell pidof $pkg 2>$null | Out-String).Trim()
    $threads = ""; $pss = ""; $gfx = ""; $gl = ""
    if ($procId) {
        $threads = (adb shell run-as $pkg ls "/proc/$procId/task" 2>$null | Measure-Object -Line).Lines
        $mem = adb shell dumpsys meminfo $pkg 2>$null
        foreach ($line in $mem) {
            if ($line -match '^\s*TOTAL PSS:\s+(\d+)') { $pss = $Matches[1] }
            elseif ($line -match '^\s*TOTAL\s+(\d+)' -and -not $pss) { $pss = $Matches[1] }
            if ($line -match '^\s*Graphics:\s+(\d+)') { $gfx = $Matches[1] }
            if ($line -match '^\s*GL mtrack\s+(\d+)') { $gl = $Matches[1] }
        }
    }
    $thermal = ((adb shell dumpsys thermalservice 2>$null | Select-String -Pattern 'Thermal Status:\s*(\d+)' | Select-Object -First 1).Matches.Groups[1].Value)
    $temp = ((adb shell dumpsys battery 2>$null | Select-String -Pattern 'temperature:\s*(\d+)' | Select-Object -First 1).Matches.Groups[1].Value)
    $tempC = if ($temp) { [math]::Round([int]$temp / 10.0, 1) } else { "" }
    "$wall,$procId,$threads,$pss,$gfx,$gl,$thermal,$tempC" | Add-Content -Path $Out -Encoding utf8
    Start-Sleep -Seconds $IntervalSec
}
