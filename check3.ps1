$content = Get-Content 'D:\Android\app-ckal0pi0u8sh\android_native\app\src\main\java\com\schedulecalendar\app\ui\calendar\CalendarScreen.kt' -Raw
$opens = ([regex]::Matches($content, '\{')).Count
$closes = ([regex]::Matches($content, '\}')).Count
Write-Host "opens=$opens closes=$closes diff=$($opens - $closes)"
