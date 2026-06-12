$ErrorActionPreference = 'Stop'
$action  = New-ScheduledTaskAction -Execute '@@EXEC@@' -Argument '@@ARGS@@' -WorkingDirectory '@@DIR@@'
$trigger = @@TRIGGER@@
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours @@TIME_LIMIT@@)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName '@@NAME@@' -TaskPath '@@TASK_FOLDER@@' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description '@@DESC@@' -Force | Out-Null
Write-Output 'OK'
