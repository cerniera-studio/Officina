# Officina

*Workshop, where the car goes to be made right.* The Cerniera studio's workshop on the phone: every app's latest build and the lamp of its last run, one tap to install, and the list of things still to see on the Fold, ticked as you test. A Cerniera app, built to the Bellezza Necessaria mandate on the shared Kit.

## What it does

- **The rack.** Every public app repo in cerniera-studio that publishes an APK, newest build first. Each carries the lamp of its last workflow run (green passed, amber running, rosso failed), the newest build (branch builds named), what the phone has, and how many checks are still to see. A build you haven't opened carries a champagne dot. Retired apps (Ferro, Giano, Curva, Ribalta, Finestra) are hidden unless asked for; archived repos are left off the rack altogether.
- **Install.** Fetches the release's APK and hands it to Android's PackageInstaller. Android asks once before Officina may install apps; after that, updates to apps Officina installed itself go through without a prompt where Android allows it. The first update of each app installed some other way still asks. Every build of an app is signed with its repo's own key, so installs are updates and keep settings.
- **The bench.** Each app's builds (newest, and latest on main if different), the last run, release notes, the checklist, and the emulator's screenshots from the newest build.
- **Still to see on the Fold.** Each repo can keep a `FOLD_CHECKS.md`:

  ```
  ## Heading
  - [ ] something to check on the phone
  - [x] something already seen
  ```

  Tick items as you see them. **Share the report** sends plain words (paste them into a chat). With a token, **Save ticks to GitHub** writes them back into the file on main.

## GitHub

Read through the REST API, with every answer kept and asked for again conditionally, so unchanged answers don't count against the hourly limit (60 an hour without a token, 5,000 with one). A token is optional: a fine-grained token for cerniera-studio with read access to Actions and Contents (and write to Contents to save ticks). It is encrypted with a key held in Android's keystore. Indagine is private and stays out.

## Get the APK

Every build on main is published as a release with `Officina.apk`; branch builds are pre-releases. After the first install, Officina can update itself.
