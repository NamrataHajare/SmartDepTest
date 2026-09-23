$java = (Get-Command java -ErrorAction Stop).Source
$classPath = Join-Path (Get-Location) 'target\classes'
$project = 'C:\Users\HP\OneDrive\Desktop\Documents\Mega Project\quickfixj'
$info = [System.Diagnostics.ProcessStartInfo]::new()
$info.FileName = $java
$info.Arguments = '-cp "' + $classPath + '" smartdeptest.Main'
$info.UseShellExecute = $false
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $false
$info.RedirectStandardError = $false
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $info
[void]$process.Start()
$process.StandardInput.WriteLine($project)
$process.StandardInput.Close()
$output = $process.StandardOutput.ReadToEnd()
$process.WaitForExit()
$output
Write-Output ('EXIT_CODE=' + $process.ExitCode)
