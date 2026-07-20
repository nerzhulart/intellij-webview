# Выделение WebView в отдельный Git-репозиторий

Этот how-to описывает, как безопасно извлечь историю каталога
`community/plugins/ui.webview` из IntelliJ monorepo, сделать каталог корнем
нового репозитория и сохранить две ветки без merge:

- `n500/263/webview-gradle` — базовая ветка;
- `n500/263/webview-19-general-refactoring` — feature-ветка с тремя
  дополнительными коммитами.

Процедура не изменяет исходный monorepo. Незакоммиченные и untracked-файлы
не экспортируются.

## Почему не worktree и не полный clone

Нельзя запускать `git-filter-repo` в исходном repo или worktree: worktree
разделяет refs и object database с основным репозиторием, а фильтрация
переписывает refs и удаляет недостижимые объекты.

Полный `git clone --no-local` безопасен, но для IntelliJ monorepo очень дорог:
Git сначала упаковывает всю доступную историю. Быстрее сразу ограничить
`git fast-export` нужным pathspec и передать полученный поток в
`git-filter-repo --stdin`. В проверенной истории это сокращает более двух
миллионов monorepo-коммитов до 99 WebView-коммитов.

## Исходные параметры

```text
Source:  D:\work\ultimate
Target:  D:\work\ui-webview
Stream:  D:\work\ui-webview.fast-export
Path:    community/plugins/ui.webview/
Base:    n500/263/webview-gradle
Feature: n500/263/webview-19-general-refactoring
```

## 1. Проверить топологию исходных веток

PowerShell:

```powershell
$Source = 'D:\work\ultimate'
$Target = 'D:\work\ui-webview'
$Stream = 'D:\work\ui-webview.fast-export'
$Subtree = 'community/plugins/ui.webview'
$Base = 'n500/263/webview-gradle'
$Feature = 'n500/263/webview-19-general-refactoring'

git -C $Source rev-parse $Base
git -C $Source rev-parse $Feature

git -C $Source merge-base --is-ancestor $Base $Feature
if ($LASTEXITCODE -ne 0) {
  throw "$Base is not an ancestor of $Feature"
}

$AllDelta = git -C $Source rev-list --count "$Base..$Feature"
$WebViewDelta = git -C $Source rev-list --count "$Base..$Feature" -- $Subtree

if ($AllDelta -ne 3 -or $WebViewDelta -ne 3) {
  throw "Expected three WebView commits; total=$AllDelta, WebView=$WebViewDelta"
}

git -C $Source log --reverse --oneline "$Base..$Feature"
```

Обе проверки количества должны вернуть `3`. Если расстояние изменилось,
нельзя использовать `feature~3` в следующих шагах без пересчёта.

## 2. Установить `git-filter-repo`

Windows:

```powershell
py -m pip install --user git-filter-repo
py -m git_filter_repo --version
```

Linux/WSL2:

```bash
python3 -m pip install --user git-filter-repo
python3 -m git_filter_repo --version
```

Запуск через `py -m` или `python3 -m` не требует добавлять Python Scripts в
`PATH`.

На Linux и WSL2 Git обычно работает быстрее. Для WSL2 source и target нужно
держать на ext4, например в `~/work`; работа через `/mnt/d` теряет большую
часть преимущества.

## 3. Создать пустой target

Target не должен содержать нужных данных:

```powershell
if (Test-Path -LiteralPath $Target) {
  throw "Target already exists: $Target"
}

git init $Target
```

Remote пока добавлять не нужно.

## 4. Экспортировать только WebView

На Windows выполнить через `cmd.exe`, чтобы shell не перекодировал
fast-export stream:

```bat
git -C D:\work\ultimate fast-export --show-original-ids --signed-tags=strip --tag-of-filtered-object=rewrite --fake-missing-tagger --reference-excluded-parents --use-done-feature --mark-tags --reencode=yes refs/heads/n500/263/webview-19-general-refactoring -- community/plugins/ui.webview/ > D:\work\ui-webview.fast-export
```

В проверенном состоянии stream занимает примерно 97 MB. Размер может
измениться после добавления новых коммитов.

Linux/WSL2:

```bash
git -C "$SOURCE" fast-export \
  --show-original-ids \
  --signed-tags=strip \
  --tag-of-filtered-object=rewrite \
  --fake-missing-tagger \
  --reference-excluded-parents \
  --use-done-feature \
  --mark-tags \
  --reencode=yes \
  refs/heads/n500/263/webview-19-general-refactoring \
  -- community/plugins/ui.webview/ \
  > "$STREAM"
```

## 5. Перенести каталог в корень нового repo

Windows (`cmd.exe`):

```bat
py -m git_filter_repo --stdin --target D:\work\ui-webview --path community/plugins/ui.webview/ --path-rename community/plugins/ui.webview/: < D:\work\ui-webview.fast-export
```

Linux/WSL2:

```bash
python3 -m git_filter_repo \
  --stdin \
  --target "$TARGET" \
  --path community/plugins/ui.webview/ \
  --path-rename community/plugins/ui.webview/: \
  < "$STREAM"
```

Двоеточие в конце `community/plugins/ui.webview/:` обязательно: старый
префикс заменяется на пустой, поэтому WebView становится корнем.

Хеши переписанных коммитов изменятся. Содержимое файлов, авторы, даты,
сообщения и релевантная топология сохранятся. Криптографические подписи
переписанных коммитов сохранить невозможно.

## 6. Создать базовую ветку

Предварительная проверка установила, что все три feature-коммита относятся к
WebView и переживают фильтрацию:

```powershell
git -C $Target branch $Base "$Feature~3"
git -C $Target switch $Feature
```

Ветки остаются отдельными; merge не выполняется.

## 7. Проверить результат

Проверка refs и ancestry:

```powershell
git -C $Target for-each-ref --format='%(refname:short) %(objectname)' refs/heads

git -C $Target merge-base --is-ancestor $Base $Feature
if ($LASTEXITCODE -ne 0) {
  throw 'Filtered base is not an ancestor of feature'
}

$Delta = git -C $Target rev-list --count "$Base..$Feature"
if ($Delta -ne 3) {
  throw "Expected three feature commits; found $Delta"
}
```

Проверка полного содержимого обеих веток:

```powershell
$SourceBaseTree = git -C $Source rev-parse "$Base`:$Subtree"
$TargetBaseTree = git -C $Target rev-parse "$Base^{tree}"
$SourceFeatureTree = git -C $Source rev-parse "$Feature`:$Subtree"
$TargetFeatureTree = git -C $Target rev-parse "$Feature^{tree}"

if ($SourceBaseTree -ne $TargetBaseTree) {
  throw "Base tree mismatch: source=$SourceBaseTree target=$TargetBaseTree"
}

if ($SourceFeatureTree -ne $TargetFeatureTree) {
  throw "Feature tree mismatch: source=$SourceFeatureTree target=$TargetFeatureTree"
}
```

Финальная проверка целостности:

```powershell
git -C $Target fsck --full
git -C $Target status --short
git -C $Target count-objects -vH
```

`dangling tree 4b825dc...` — безвредный пустой tree от `git init`. Ошибок
missing/corrupt быть не должно. В проверенном результате ветки содержат 96 и
99 коммитов, а pack занимает около 23 MB.

## 8. Удалить временный stream

Только после успешной сверки tree SHA:

```powershell
Remove-Item -LiteralPath $Stream
```

Linux/WSL2:

```bash
rm -- "$STREAM"
```

Исходный monorepo удалять или очищать не нужно.

## 9. Подготовить публикацию

Перед push:

1. Проверить всю отфильтрованную историю на credentials, внутренние URL и
   файлы, которые нельзя публиковать.
2. Добавить корректные standalone `LICENSE` и `NOTICE`: внутри исходного
   каталога monorepo-level лицензии отсутствуют.
3. Создать пустой remote без автоматически сгенерированного README.
4. Запушить обе ветки явно.

```powershell
git -C $Target remote add origin <repository-url>
git -C $Target push -u origin $Base
git -C $Target push -u origin $Feature
```

Merge не требуется: base остаётся предком feature, а feature сохраняет три
дополнительных коммита.

## Troubleshooting

### `git-filter-repo` не найден

```powershell
py -m pip install --user --upgrade git-filter-repo
py -m git_filter_repo --version
```

### Полный clone занимает слишком много времени или памяти

Не использовать полный `git clone --no-local` для этого monorepo. Выполнить
path-limited `git fast-export` и передать stream в
`git_filter_repo --stdin`, как описано выше.

### Tree SHA не совпадают

Не выполнять push. Проверить, что:

- source refs не изменились во время экспорта;
- path равен `community/plugins/ui.webview/`;
- выражение rename заканчивается на `/:`;
- target был пустым;
- не ожидалось переноса незакоммиченных файлов.

После исправления повторить процедуру в новом пустом target.

### Feature больше не на три коммита впереди

Повторить preflight-проверки. Нельзя автоматически создавать base через
`feature~3`, если исходная топология изменилась.
