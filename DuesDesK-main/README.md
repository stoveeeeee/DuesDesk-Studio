# DuesDesk

DuesDesk is a desktop finance and account-management app for fraternity and chapter treasurers. It brings member dues, house rent, payment activity, and collection reporting together in one place so officers can keep balances current and understand the chapter's cash position.

## What you can do

- **Manage member dues:** Maintain a chapter roster with member contact details, amounts owed, past-due balances, payment history, charges, and installment plans. Record payments and review individual account statements and receipts.
- **Track house rent:** Manage residents, rooms, monthly rent, balances, and payments separately from member dues.
- **Review collections and finances:** See collection and cash-flow figures, explore metric history, track progress toward an International Headquarters (IHQ) bill, and use the spending calculator to compare a proposed expense with income and outstanding obligations.
- **Follow activity over time:** Use the calendar to review money in and out by date and record payments. The Cash Flow view combines dues payments and imported Venmo activity.
- **Import and reconcile data:** Import GreekBill balance exports to sync member balances, Venmo statements to record completed transactions, and bank CSVs to review incoming deposits. Bank transactions are not applied to member balances until a member match is confirmed.
- **Send reminders and announcements:** Prepare individual or bulk dues and rent text messages, configure reminder schedules and message templates, and review the dispatch log. Live SMS requires a connected Twilio account; without live credentials, the app runs in simulation mode.
- **Back up and move data:** Export a password-encrypted AES backup and import it on another computer. Importing a backup replaces the data currently in the app. On macOS and Windows you can also enable daily encrypted snapshots to a local iCloud Drive, OneDrive, Dropbox, or Google Drive folder; the automatic backup key is held in the macOS Keychain or protected by Windows Data Protection (DPAPI).
- **Prepare officer transitions and reports:** Generate an encrypted handoff ZIP with a one-page financial summary and setup instructions, an anonymous chapter meeting packet, and a social-clearance PDF or CSV that excludes balances.
- **Manage chair budgets:** Find chairs with color-coded cards, initials, filtering, and jump navigation. Edit names, budget caps, goals, and colors without losing activity, or delete a chair with confirmation. Record reimbursements and attach local PNG/JPEG receipts.
- **Send reminders without Twilio:** Next to anyone who owes money on Dues or Rent, open a pre-filled reminder in your email app or Messages (Phone Link on Windows), or copy it. Add the chapter's Venmo, Cash App, PayPal, or Zelle accounts in Settings and use `{{venmo_link}}` (or `{{pay_links}}`) in templates for a two-tap payment link with the amount and a matching note filled in.
- **Remember who pays for whom:** When confirming an imported deposit, tick "Remember this payer name" to have future payments from a parent or roommate suggested for that member. Bank deposits are still applied only after you confirm.
- **Payment plan agreements:** Generate a printable agreement for any member on installments, with the schedule, the late-fee and clearance policy, and signature lines.
- **Start a new semester:** Finance → Reports & Handoff can archive paid-up members who left, fold unpaid charges into one carryover balance, clear expired vouches, and start chair budgets fresh. A copy of the data is saved first, and payment history is kept.
- **Set dues policies:** Configure disabled-by-default late fees for itemized overdue charges and social-clearance criteria, including a balance tolerance, eligible payment plans, and expiring manual vouches.

## Typical uses

1. Add or import the chapter roster and review each member's balance.
2. Record payments, add charges or installments, and check who is past due.
3. Reconcile imported bank activity by confirming the correct member for each deposit.
4. Review collection rates and cash flow before making a spending decision.
5. Track resident rent separately, then send reminders to residents with balances due.
6. Export a backup when moving DuesDesk or safeguarding the chapter's records.
7. Configure cloud snapshots and dues policies in Settings. Use Finance → Reports & Handoff for handoff packages and chapter reports, and Finance → Chair Budgets for chair budgets and reimbursements.

## Using the dues dashboard

- **Layout:** The home page shows the summary stats, then **Needs attention**, then the member roster (the Member Dues Ledger). Bulk tools sit below the roster.
- **Bulk tools stay out of the way:** **Text members by status** and **Create charges for selected members** start collapsed. Hover over one to preview it; it collapses again when the pointer leaves. Clicking or typing inside a tool keeps it open, even after you move to other parts of the page. Click the tool's title to open or close it manually.
- **Review keeps the ledger visible:** Needs attention Review links open the member ledger in full-screen mode, keep the column headers visible, and center and highlight the selected member.
- **You stay where you were:** After saving, deleting, muting, adding a charge, or any other action, the page reopens at the same scroll position, and the confirmation message appears just below the navigation bar. Changing the roster sort, paging through the roster, and switching calendar months also keep your place. Navigation links still open pages at the top.
- **Desktop window appearance:** In the desktop app, cards, buttons, and inputs use square corners, while status badges and pills stay rounded. The embedded browser redraws the whole window on every scroll step, and rounded corners roughly doubled the cost of each redraw, which made scrolling choppy.

## Backup and report details

Manual backup files use a password-derived AES-GCM key; keep the password separate from the file. Automatic snapshots work on macOS (key in the macOS Keychain) and Windows 10/11 (key protected by Windows Data Protection for your Windows account, stored under %LOCALAPPDATA%\DuesDesk). Settings can reveal the generated recovery password; record it separately so you can restore synced snapshots if the original computer is unavailable. Snapshots run daily at 2:00 AM, and also when DuesDesk starts or quits if no snapshot was written in the last 20 hours, so a laptop that sleeps overnight still gets a daily backup. Old snapshots beyond the retention period are pruned. Settings offers quick-pick buttons for the cloud-sync folders found on the computer.

Social clearance is based on the dues balance tolerance by default. If dues-only evaluation is disabled, rent counts for the resident linked to that member on the Rent page; residents nobody has linked yet are matched by name (ignoring case and extra spaces), and existing residents are linked automatically when the names match. An active payment plan with no overdue installments or a manual vouch through its expiration date can also clear a member. The exported list contains names and clearance states only.

Late fees are disabled by default and are applied once per itemized charge after its due date plus the configured grace period. The meeting packet estimates cash as opening cash plus imported ledger activity; enter upcoming bills in Settings. Chair receipts are limited to PNG/JPEG images of at most 5 MB; photos wider than 1600 pixels are resized and saved as JPEGs so they stay legible without bloating the data file and handoff ZIP.

## How it works

DuesDesk is packaged as a Java desktop application. Its interface runs in an embedded browser window, while a local Spring Boot service handles the app's data and workflows. Application records are stored locally in a data file; exported backups contain sensitive information such as names, phone numbers, and balances, so store them securely. Twilio credentials are kept separately and are not included in data exports.

The project uses Maven and requires Java 25 to build and run.

## Where data is stored

New installs keep their data in `~/Library/Application Support/DuesDesk` on macOS and `%LOCALAPPDATA%\DuesDesk` on Windows. Installs that already have data in `~/.fraternity-dues` keep using it. Set the `STORE_FILE_PATH` environment variable to use a specific file.

## Running with Docker

The container runs the web service only (no desktop window). Build and start it with:

```
docker compose up -d --build
```

Then open <http://localhost:8080>. Data and the backup key are kept in the `duesdesk-data` volume (`/data` in the container), so they survive rebuilds. DuesDesk has no login, so `compose.yaml` publishes the port on `127.0.0.1` only; don't expose it to a network without adding authentication. To enable live SMS, set `TWILIO_SIMULATION_MODE=false` plus `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` and `TWILIO_FROM_NUMBER` in your shell or a `.env` file next to `compose.yaml`.

## Building installers

JavaFX native libraries are chosen for the computer that builds, so build each installer on its own OS:

- **macOS** (`.dmg`): `packaging/package-mac.sh`
- **Windows** (`.msi`, or `.exe` with `-Type exe`): `powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1` (requires the WiX Toolset)

Both run `mvn -Pjpackage -DskipTests clean package` and write to `target/installer`.
