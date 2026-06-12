
# Open the finished report (no .md association -> resolve an editor by path).
$editors = @(
    "$env:LOCALAPPDATA\Programs\Microsoft VS Code\Code.exe",
    'C:\Program Files\Microsoft VS Code\Code.exe',
    'C:\Program Files\Notepad++\notepad++.exe',
    "${env:ProgramFiles(x86)}\Notepad++\notepad++.exe")
$editor = $editors | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $editor) { $editor = "$env:WINDIR\System32\notepad.exe" }
Start-Process -FilePath $editor -ArgumentList "`"$out`""
