<#
Finds every Talks & Podcasts folder on the Y2's card, looks each one up in the
Apple Podcasts directory and saves its cover as folder.jpg, so the player shows
real artwork. It never replaces a folder.jpg or cover.jpg that is already there.

Run it once with the card in your PC:
  1. Double-click "Find talk artwork.cmd" (it runs this script).
  2. It lists every folder and the show it matched. Nothing is saved yet.
  3. Check the list, then answer Y to save the covers.

The same folders count as talks as in the player: any folder whose name says
Talks, Podcast(s), Katha, Lecture(s), Sermons, NDE(s), Vichar, Audiobook(s),
Plum Village or Near Death. One cover in a speaker's folder (Jvala Singh)
covers all of their podcasts; a podcast's own folder.jpg wins over it.
#>
param(
    [string]$Drive = "",
    # A folder to treat as the card, instead of a drive letter.
    [string]$Path = "",
    [switch]$Yes
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$RootWords = @("talks","talk","podcast","podcasts","katha","kathas","audiobook","audiobooks",
               "lecture","lectures","sermons","nde","ndes","vichar")
$RootPhrases = @("plum village","near death","near-death")
$AudioExt = @(".mp3",".m4a",".m4b",".aac",".ogg",".opus",".flac",".wav",".wma")
$CoverNames = @("folder.jpg","folder.jpeg","folder.png","cover.jpg","cover.jpeg","cover.png")

function Test-TalksRoot([string]$name) {
    $n = $name.Trim().ToLowerInvariant()
    if ($n -eq "talks & podcasts") { return $true }
    foreach ($p in $RootPhrases) { if ($n.Contains($p)) { return $true } }
    foreach ($w in ($n -split "[^\p{L}\p{Nd}_]+")) { if ($RootWords -contains $w) { return $true } }
    return $false
}

function Test-HasAudio([IO.DirectoryInfo]$dir) {
    return [bool](Get-ChildItem -LiteralPath $dir.FullName -File -ErrorAction SilentlyContinue |
        Where-Object { $AudioExt -contains $_.Extension.ToLowerInvariant() } | Select-Object -First 1)
}

function Test-HasCover([IO.DirectoryInfo]$dir) {
    return [bool](Get-ChildItem -LiteralPath $dir.FullName -File -ErrorAction SilentlyContinue |
        Where-Object { $CoverNames -contains $_.Name.ToLowerInvariant() } | Select-Object -First 1)
}

# Finds the card: the drive given, or the first removable drive with a talks folder on it.
if (-not $Drive -and -not $Path) {
    $candidates = Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Root -and (Test-Path $_.Root) }
    foreach ($c in $candidates) {
        $top = Get-ChildItem -LiteralPath $c.Root -Directory -ErrorAction SilentlyContinue
        if ($top | Where-Object { Test-TalksRoot $_.Name }) { $Drive = $c.Name; break }
        $music = $top | Where-Object { $_.Name -ieq "Music" } | Select-Object -First 1
        if ($music -and (Get-ChildItem -LiteralPath $music.FullName -Directory -ErrorAction SilentlyContinue | Where-Object { Test-TalksRoot $_.Name })) {
            $Drive = $c.Name; break
        }
    }
}
if (-not $Drive -and -not $Path) { $Drive = Read-Host "Which drive letter is the Y2's card (for example E)" }
$root = if ($Path) { (Resolve-Path $Path).Path.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar } else { ($Drive.TrimEnd(':','\') + ":\") }
if (-not (Test-Path $root)) { throw "Drive $root not found." }
Write-Host "Card: $root`n"

# Every talks folder, then the folders to give covers: the speaker folder just
# inside the talks folders, or the podcast itself when it sits directly inside.
$targets = New-Object System.Collections.Generic.List[object]
$seen = @{}
$talkRoots = Get-ChildItem -LiteralPath $root -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue |
    Where-Object { (Test-TalksRoot $_.Name) -and -not (Test-TalksRoot $_.Parent.Name) }
foreach ($talkRoot in $talkRoots) {
    $queue = @($talkRoot)
    while ($queue.Count -gt 0) {
        $dir = $queue[0]; $queue = @($queue | Select-Object -Skip 1)
        foreach ($child in (Get-ChildItem -LiteralPath $dir.FullName -Directory -ErrorAction SilentlyContinue)) {
            if (Test-TalksRoot $child.Name) { $queue += $child; continue }
            if ($seen.ContainsKey($child.FullName)) { continue }
            $seen[$child.FullName] = $true
            $podcasts = @(Get-ChildItem -LiteralPath $child.FullName -Directory -Recurse -ErrorAction SilentlyContinue | Where-Object { Test-HasAudio $_ })
            if (Test-HasAudio $child) { $podcasts += $child }
            if ($podcasts.Count -eq 0) { continue }
            $kind = if ($podcasts.Count -eq 1 -and $podcasts[0].FullName -eq $child.FullName) { "Podcast" } else { "Speaker" }
            $targets.Add([pscustomobject]@{ Folder = $child; Kind = $kind; Podcasts = $podcasts.Count })
        }
    }
}
if ($targets.Count -eq 0) { Write-Host "No talks folders found on $root."; exit }

function Find-Cover([string]$name) {
    $term = [Uri]::EscapeDataString($name)
    $url = "https://itunes.apple.com/search?media=podcast&limit=3&term=$term"
    try { $r = Invoke-RestMethod -Uri $url -TimeoutSec 20 } catch { return $null }
    $hit = $r.results | Select-Object -First 1
    if (-not $hit) { return $null }
    $art = if ($hit.artworkUrl600) { $hit.artworkUrl600 } else { $hit.artworkUrl100 -replace "100x100","600x600" }
    return [pscustomobject]@{ Show = $hit.collectionName; By = $hit.artistName; Art = $art }
}

$plan = foreach ($t in $targets) {
    $has = Test-HasCover $t.Folder
    $match = if ($has) { $null } else { Find-Cover $t.Folder.Name }
    [pscustomobject]@{
        Folder   = $t.Folder.FullName.Substring($root.Length)
        Kind     = $t.Kind
        Podcasts = $t.Podcasts
        Status   = if ($has) { "Has a cover already" } elseif ($match) { "Found" } else { "Not found" }
        Match    = if ($match) { "$($match.Show) - $($match.By)" } else { "" }
        Art      = if ($match) { $match.Art } else { "" }
        Path     = $t.Folder.FullName
    }
    Start-Sleep -Milliseconds 300
}

$plan | Format-Table -AutoSize Folder, Kind, Podcasts, Status, Match | Out-String -Width 220 | Write-Host
$list = Join-Path $PSScriptRoot "talks-folders.csv"
$plan | Select-Object Folder, Kind, Podcasts, Status, Match, Art | Export-Csv -NoTypeInformation -Encoding UTF8 $list
Write-Host "The full list is saved as $list (open it in Excel).`n"

$toSave = @($plan | Where-Object { $_.Status -eq "Found" })
if ($toSave.Count -eq 0) { Write-Host "Nothing new to save."; exit }
if (-not $Yes) {
    $answer = Read-Host "Save $($toSave.Count) covers as folder.jpg? Check the Match column first. (Y/N)"
    if ($answer -notmatch "^[Yy]") { Write-Host "Nothing saved."; exit }
}
foreach ($item in $toSave) {
    $target = Join-Path $item.Path "folder.jpg"
    try {
        Invoke-WebRequest -Uri $item.Art -OutFile $target -TimeoutSec 30 -UseBasicParsing
        Write-Host "Saved  $($item.Folder)"
    } catch {
        Write-Host "Failed $($item.Folder): $($_.Exception.Message)"
    }
}
Write-Host "`nDone. Put the card back in the Y2; covers appear straight away."
Write-Host "A wrong match? Delete that folder.jpg, or replace it with any square image named folder.jpg."
