$content = Get-Content 'D:\Android\app-ckal0pi0u8sh\android_native\app\src\main\java\com\schedulecalendar\app\ui\calendar\CalendarScreen.kt' -Raw
$opens = ([regex]::Matches($content, '\{')).Count
$closes = ([regex]::Matches($content, '\}')).Count
Write-Output "Opens: $opens"
Write-Output "Closes: $closes"
Write-Output "Diff: $($opens - $closes)"
$lines = $content -split "`n"
Write-Output "Total lines: $($lines.Count)"
# Show lines around CalendarScreen close
for ($i = 418; $i -le 428; $i++) {
    Write-Output "$($i+1): $($lines[$i])"
}
