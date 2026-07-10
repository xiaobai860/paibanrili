$filePath = 'D:\Android\app-ckal0pi0u8sh\android_native\app\src\main\java\com\schedulecalendar\app\ui\calendar\CalendarScreen.kt'
$lines = Get-Content $filePath
Write-Output "Total lines: $($lines.Count)"

# Check brace balance
$openBraces = 0
$closeBraces = 0
foreach ($line in $lines) {
    foreach ($ch in $line.ToCharArray()) {
        if ($ch -eq '{') { $openBraces++ }
        if ($ch -eq '}') { $closeBraces++ }
    }
}
Write-Output "Open braces: $openBraces"
Write-Output "Close braces: $closeBraces"
Write-Output "Diff (open-close): $($openBraces - $closeBraces)"

# Show lines 155-200 (topBar area)
Write-Output "`n--- Lines 155-200 (topBar area) ---"
for ($i = 155; $i -le 200; $i++) {
    Write-Output "${i}: $($lines[$i-1])"
}

# Show lines 355-430 (content area dialogs)
Write-Output "`n--- Lines 355-430 (dialogs area) ---"
for ($i = 355; $i -le 430; $i++) {
    Write-Output "${i}: $($lines[$i-1])"
}

# Show lines 725-745 (DayCell end + TodoCenter start)
Write-Output "`n--- Lines 725-745 (DayCell end) ---"
for ($i = 725; $i -le 745; $i++) {
    Write-Output "${i}: $($lines[$i-1])"
}

# Show last 20 lines
Write-Output "`n--- Last 20 lines ---"
$start = $lines.Count - 19
for ($i = $start; $i -le $lines.Count; $i++) {
    Write-Output "${i}: $($lines[$i-1])"
}
