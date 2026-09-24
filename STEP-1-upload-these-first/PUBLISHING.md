# Putting this on GitHub

A step-by-step guide for the first time. Written assuming you have never used git
or GitHub before.

You only do **Part 1 and 2** once. After that, publishing a new version is three
commands (Part 3).

---

## Part 1: One-time setup

### 1.1 Install Git

Open **PowerShell** and run:

```powershell
winget install --id Git.Git --source winget
```

Then **close PowerShell and open a new one** (the installer changes your PATH,
and existing windows do not pick that up). Check it worked:

```powershell
git --version
```

You should see something like `git version 2.x.x`.

### 1.2 Tell Git who you are

This goes into every commit you make. Run once:

```powershell
git config --global user.name "Jakub"
git config --global user.email "david.svozil@gmail.com"
```

> Your email becomes publicly visible in the commit history. If you would rather
> it did not, GitHub can give you a private no-reply address: go to
> **Settings → Emails → Keep my email addresses private**, then use the
> `...@users.noreply.github.com` address it shows you instead.

### 1.3 Create a GitHub account

If you do not have one: [github.com/signup](https://github.com/signup). Free.

### 1.4 Create an empty repository

1. Go to [github.com/new](https://github.com/new)
2. **Repository name:** `lucid-dreamer`
3. **Description:** `Open-source Android toolkit for lucid dreaming. Offline, no ads, no tracking.`
4. Choose **Public** (anyone can see it) or **Private** (only you).
5. **Important:** leave *"Add a README file"*, *"Add .gitignore"* and *"Choose a license"*
   all **unticked**. This project already has all three, and ticking them creates
   a conflict you would then have to untangle.
6. Click **Create repository**.

GitHub will show you a page with some commands. Ignore it — the next part covers it.

---

## Part 2: Upload the code

Open PowerShell and run these **one at a time**, from the project folder:

```powershell
cd C:\Users\Jakub\Downloads\lucid-dreamer

git init
git branch -M main
git add .
```

Before committing, check that the 700 MB toolchain folder is **not** included:

```powershell
git status --short | Select-String ".toolchain"
```

This should print **nothing**. If it prints file paths, stop and ask for help —
something is wrong with `.gitignore`, and you do not want to upload that folder.

Now commit and push:

```powershell
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR-USERNAME/lucid-dreamer.git
git push -u origin main
```

Replace `YOUR-USERNAME` with your actual GitHub username.

**A browser window will open asking you to sign in to GitHub.** That is normal —
Git for Windows handles authentication that way. Sign in, approve, and the push
continues. You will not have to do this again.

When it finishes, refresh your repository page. All the code and documentation
will be there.

### If something goes wrong

| Message | What it means |
|---|---|
| `remote origin already exists` | You ran `git remote add` twice. Use `git remote set-url origin <url>` instead. |
| `failed to push some refs` | The GitHub repo is not empty. Easiest fix: delete it on GitHub and create a new empty one. |
| `git: command not found` | You did not open a new PowerShell window after installing Git. |

---

## Part 3: Publish a downloadable APK

Uploading the code does not automatically give people an app to install. For
that you create a **release**, and the build workflow in this repository does the
rest.

```powershell
git tag v0.1.0
git push origin v0.1.0
```

That is it. Pushing a tag beginning with `v` triggers GitHub Actions, which will:

1. Run all 124 tests and the linter
2. Build the APK
3. Create a Release with the APK attached, plus a `SHA256SUMS.txt` so people can
   verify their download

Watch it happen under the **Actions** tab of your repository. It takes about five
minutes the first time. When it finishes, the **Releases** section on your
repository's front page will have a downloadable `.apk`.

### For the next version

Edit `versionName` in `app/build.gradle.kts`, add a section to `CHANGELOG.md`,
then:

```powershell
git add .
git commit -m "Version 0.2.0"
git push
git tag v0.2.0
git push origin v0.2.0
```

Tags must be unique — you cannot reuse `v0.1.0`.

### Optional: signed releases

Without setup, the published APK is signed with Android's standard debug key. It
installs and works perfectly; Android just warns that the developer is unknown,
and the release notes say so.

To publish properly signed builds, create a keystore
(see [docs/INSTALL.md](docs/INSTALL.md)), then add four repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Your `.jks` file, base64-encoded |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

To base64-encode the keystore on Windows:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\lucid-release.jks")) | Set-Clipboard
```

That copies it to your clipboard, ready to paste into the secret.

> **Keep the keystore file and its passwords safe and backed up.** If you lose
> them, existing users cannot update in place — they would have to uninstall
> first, which deletes their dream journal.

---

## Part 4: How other people get the app

Once a release exists, anyone can:

1. Go to `github.com/YOUR-USERNAME/lucid-dreamer`
2. Click **Releases** on the right-hand side
3. Download the `.apk` under **Assets**
4. Open it on their Android phone and allow the install

[DOWNLOAD.md](DOWNLOAD.md) in this repository explains all of that in detail,
including the Samsung-specific steps, and the release notes link straight to it.

---

## About the README

**You do not need to write one.** `README.md` already exists and covers
everything a visitor needs:

- What the app does, and honestly what it cannot do
- A prominent download link that points at your Releases page
- A feature list and the design principles
- Requirements, project status, and the licence
- Links to all the other documentation

The download links use *relative* paths (`../../releases/latest`), so GitHub
resolves them to your repository automatically. **There is nothing to
personalise** — they will work the moment you push, whatever your username is.

The only things worth editing later, once you have used the app:

- Add screenshots (drag images into a GitHub issue to get a URL, then paste it
  into the README)
- Add your own notes about what worked for you

### Making it look good on GitHub

Two small things worth doing on the repository page itself:

- Click the gear icon next to **About** and add a description plus topics like
  `android`, `kotlin`, `lucid-dreaming`, `sleep`, `privacy`, `offline`. Topics are
  how people find projects.
- Tick **Releases** in that same panel so the download is visible on the front page.
