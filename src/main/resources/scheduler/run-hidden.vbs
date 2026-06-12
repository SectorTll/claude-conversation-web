' Hidden launcher: wscript has no console, runs PowerShell with window style 0.
Dim sh, rc
Set sh = CreateObject("WScript.Shell")
rc = sh.Run("powershell.exe -NoProfile -ExecutionPolicy Bypass -File ""@@PS1@@""", 0, True)
WScript.Quit rc
