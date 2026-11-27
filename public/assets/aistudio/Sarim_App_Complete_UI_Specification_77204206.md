# Sarim App: Complete UI Specification

*Gull and Zubair Naswar Dealers · UI-only reference · Built from the repository github.com/mkmehmood/sarim (latest commit 99f1b6f) and the 305 screenshots in the supplied interaction capture*

## 1. How to read this document

**Scope.** This document describes only what a person sees and touches: screens, navigation, input fields, buttons, toggles, cards, charts, dialogs and visual style. Business rules (profit formulas, sync, storage) are mentioned only where they change something on screen.

**Sources used.**

- `index.html` (the main shell and the seven main tabs)
- `screens/*.screen.html` (eleven standalone screens kept as separate files)
- Markup generated at run time by the JavaScript modules (Overview, login, dialogs, history cards, pop-ups)
- `app.css` (colours, fonts, component styles)
- Screenshots: phone portrait, 824 × 1830 px, dark theme, grouped by screen

**Conventions.**

- **ID** is the element id in the page, so a developer can find the control quickly.
- **(dynamic)** means the item is built by code from stored data, so its number of entries changes (for example the list of stores).
- **(role)** means the item is shown only to certain account roles (see section 11).
- Customer names, phone numbers and balances that appear in the screenshots are real business data. They are deliberately left out and replaced with generic descriptions.
- Where something could not be confirmed from code or screenshots, the text says so.

## 2. The app at a glance

| Item | Detail |
| --- | --- |
| Product name | Gull and Zubair Naswar Dealers (the sidebar shows GULL AND ZUBAIR) |
| Purpose | Production, sales, inventory, credit and cash tracking for a naswar (snuff) manufacturing and dealing business |
| Form | Offline-first web app (PWA). The repository also contains an Android wrapper and a desktop wrapper. |
| Language of the interface | English. The font stack includes an Urdu (Nastaliq) fallback so Urdu names display correctly. |
| Currency and units | Rupees (shown as `Rs` or `₨`), weights in kg, large numbers in the South Asian grouping (for example 14,09,764) |
| Default theme | Dark (pure black background, green accent) |
| Main tabs | 7: Overview, Production (titled Manufacturing), Sales, Calculator, Factory, Payments, Rep Sales |
| Standalone screens | 24 full-screen pages that slide over the tabs (listed in section 5) |
| Navigation style | One hamburger button opening a left drawer. There is **no bottom tab bar**. |

### 2.1 Screen map

```
Splash screen
   └─ Sign-in screen ──(optional)── Fingerprint lock
        └─ App shell (top bar + one active tab)
             ├─ Drawer (hamburger)
             │    ├─ NAVIGATION: Overview · Production · Sales · Calculator · Factory · Payments · Rep Sales
             │    ├─ DAILY WORK: Approvals · Daily Ledger · Stock Transfer · Payment Transfer
             │    ├─ RECORDS: Add Entity · Add Raw Materials · Formula Store
             │    └─ Settings · Logout
             ├─ Settings
             │    ├─ Data & Sync: Sync Data · Backup & Restore · Recycle Bin · Database Structure
             │    ├─ Appearance & Device: Theme · Sound & Haptics · Start with Computer (desktop only)
             │    ├─ Security: Fingerprint Lock · App Accounts
             │    ├─ Business: Store Manager
             │    └─ Danger Zone: Close Financial Year
             └─ Detail screens opened from lists
                  Customer · Rep customer · Entity · Expense · Customer edit · Rep customer edit
```

## 3. Visual design system

### 3.1 Themes

Three theme choices exist. The Settings button cycles through them and its label shows the current one: **THEME: DARK**, **THEME: AUTO** (follows the phone), **THEME: LIGHT**.

| Token | Dark | Light |
| --- | --- | --- |
| Background | `#000000` | `#FFFFFF` |
| Secondary surface | `#0A0A0A` | `#F5F5F5` |
| Main text | `#FFFFFF` | dark ink |
| Accent (primary green) | `#4ADE80` | `#10B981` |
| Warning / credit amber | `#FBBF24` | `#D97706` |
| Danger / cost red | `#F87171` | `#DC2626` |
| Purple | `#C084FC` | `#7C3AED` |
| Cyan | `#67E8F9` | `#0891B2` |
| Card border | white at 14% | soft grey-green |

**Meaning of colour.** Green means money in, profit, success or the active choice. Amber means credit, pending or a caution. Red means cost, money out, shortage or danger. Purple and cyan identify stores and stock. Store colours are fixed: Zubair green, Mahmood purple, Asaan cyan. Cash is green and Credit is amber everywhere.

### 3.2 Typography

| Use | Font |
| --- | --- |
| Tab titles and big figures (shimmering headings) | Playfair Display (serif) |
| Brand lettering and standalone-screen titles | Cinzel (capital serif) |
| Body text, labels, buttons | Plus Jakarta Sans, with Noto Nastaliq Urdu fallback |
| Numbers in tables and tiles | Manrope with tabular figures so digits line up |
| Code-like values | JetBrains Mono |

All fonts are bundled in the `fonts/` folder, so the app looks the same offline.

### 3.3 Component catalogue

| Component | What it looks like and how it behaves |
| --- | --- |
| **Card** | Rounded rectangle with a thin border and a soft glow. Cards are the main grouping element on every tab. |
| **Toggle group** (`.toggle-opt`) | A row of pill buttons where exactly one is highlighted (green border and green text). Used for modes (Sale / Collection), periods (Daily / Weekly / Monthly / Yearly / All Time), stores and payment type. |
| **Text and number field** (`.form-ctl`) | Dark rounded box with a small upper-case label above it. Focus turns the border green. Numeric fields ask for the decimal keypad. |
| **Affixed field** | A field with a fixed unit inside the box: `Rs` on the left, `kg` or `/kg` on the right. |
| **Primary button** (`.btn-main`) | Full-width pill with a cyan-to-lime gradient and dark text. It is the single save action of a form. |
| **Secondary button** (`.btn-theme`) | Outlined pill. Used for Clear, Refresh, Cancel Edit and icon buttons. |
| **Danger button** | Red or orange-red pill (Delete, Empty Bin, Delete Material). |
| **Pop-over selector** | Replaces the phone's native dropdown. A field with a chevron opens a small list under it. A hidden real `<select>` sits behind it and holds the value. Used for representative, chart metric, period filter and recycle bin filter. |
| **Search dropdown** | While typing in a name field, a list of matches appears under the field (customer, entity, material, supplier, channel, party). Choosing one fills the field. |
| **Stat tile** | Small bordered box with a tiny label and a large value. A coloured edge shows the meaning: yellow for quantity, red for cost, green for profit or revenue. |
| **Hero value** | One very large number at the top of a summary card, with a small caption. |
| **Summary card** | Card with a title, optional chip, hero value and a grid of stat tiles. |
| **Record list** | Scrollable list of rows. Each row has a round coloured avatar with initials, a name, a sub-line and a right-aligned amount. A two-cell footer shows totals. |
| **History card** | Expandable row used in every history list: icon or initials, title, sub-line, amount and a small tag on the right. Tapping expands a panel of metric tiles and more sections. |
| **Accordion history** | Year row, then month rows, then day rows, each showing an entry count and a total, each opening on tap. |
| **Chip and badge** | Small tinted pill for status such as PENDING, PAID, Clean, 48 warnings. |
| **Callout** | Tinted box. Amber for warnings, plain for hints, red for shortage. |
| **Checkbox row** (`.pf-check`) | Checkbox with a label, for example *Add to Daily Ledger*. |
| **Charts** | Drawn as SVG: smooth multi-line charts with legend chips, column charts, ranked horizontal bars, donut and pie charts, tiny sparkline waves inside KPI tiles. Touch shows a tooltip. |
| **Shimmer heading** | The tab title has a light sweep animation. |
| **Empty state** | Centered title and one explaining sentence, for example *Recycle Bin is empty*. |

### 3.4 Global overlays

**Dialog (confirm, alert, choice, password).** A glass card over a dimmed page. From top to bottom: a small chip with an icon and the tone word (Confirm, Permanent, Warning, Error, Success), a large round icon, a title, a body, an optional highlighted figure (label and value), and a footer with one or two buttons (a ghost button and a solid button). The tone is picked from the wording: delete-like words give the red *Permanent* tone with a bin icon, words such as *insufficient* or *exceed* give the amber *Warning* tone. Body lines that look like *Label: value* are shown as a neat fact list, and lines starting with a bullet are shown as bullet points. A password dialog adds one password field. The page behind is locked while a dialog is open.

**Custom date picker.** Every date field looks like a normal field showing the date as `26 NOV 2026` with a small calendar icon. Tapping it opens a calendar pop-over: previous and next arrows, a title such as *November 2026*, weekday headers Su to Sa, days as round cells, today marked with a dot, the selected day filled green. Tapping the title switches to a month grid, then to a year grid in pages of twelve years.

**On-screen keyboard.** The app can show its own keyboard docked at the bottom instead of the phone keyboard. Layouts:

- *Number pad*: digits 1 to 9 in a grid, `0`, `00`, `.`, calculator keys `+ − × ÷ % =`, a space key, a backspace key (hold to repeat), an Enter key, and keys to switch to Letters or Symbols.
- *Letters*: three QWERTY rows.
- *Symbols*: digits row plus punctuation rows.
- *Tools row*: Paste, Select all, and Snippets (saved pieces of text, up to 30, each up to 500 characters). Key presses use the Sound & Haptics settings. Password fields show masked text, with an eye button to reveal.

**Offline banner.** A slim bar showing a status dot and *Offline — changes saved locally*, plus a badge such as *0 pending* that counts changes waiting to upload.

**Photo viewer.** Full-screen black viewer with a zoom label (1×), close button, minus / Fit / plus buttons, and the hint *Pinch or scroll to zoom · double-tap to zoom in or out · swipe down to close*.

**Camera.** Full-screen camera with a close button, title, grid toggle, a viewfinder with focus ring, flashlight button, zoom badge and zoom pills, and a bottom row: gallery button, round shutter, switch-camera button. Several gallery images can be picked at once.

**Transaction sheet.** A bottom sheet (`cm-sheet`) with a dimmed backdrop that shows the details of one transaction opened from a customer history.

**Notifications.** Important events (for example an approval result) are sent as system notifications on the phone or browser, and tapping one opens the related record.

## 4. App shell and start-up

### 4.1 Splash screen

Shown first for a moment. It contains a decorative dial graphic with a greeting (`splash-greet`), a live clock (`splash-clock`) and the date (`splash-date`), then the brand *Gull and Zubair / Naswar Dealers*, a quotation with its author, and the footer text *Preparing your day*.

### 4.2 Sign-in screen

A full-screen overlay with soft animated colour orbs behind it.

| Element | Detail |
| --- | --- |
| Logo and brand | Round logo image, **GULL AND ZUBAIR**, *Naswar Dealers* |
| Title and sub-title | *Welcome back* / *Sign in to unlock and sync your business data.* |
| Email address (`auth-email`) | Email field with envelope icon. Placeholder `name@example.com`. No auto-capitalise, no spell-check. |
| Password (`auth-password`) | Password field with lock icon and an eye button (`auth-eye`) that toggles show and hide. Placeholder *Enter your password*. |
| Sign in | Gradient submit button |
| Message line (`auth-message`) | Shows errors such as *Sign in failed.* and status text |
| Divider *or* and **Continue with Google** | Shown only in the web version. Hidden in the Android and desktop apps. |
| Help text | *No account? **Contact the administrator** to have yours added.* There is no self-registration. |
| Security note | Shield icon and *End-to-end encrypted · AES-256-GCM* |

The on-screen keyboard appears above this screen and the layout leaves room so the focused field stays visible. A saved email can be pre-filled for offline sign-in.

### 4.3 Fingerprint lock

When switched on in Settings, the app asks for the phone's fingerprint after start-up. The Settings label changes between *Enable Fingerprint Lock* and *Disable Fingerprint Lock*.

### 4.4 Top bar (`sys-header`)

The same bar sits above every main tab: a hamburger button on the left (opens the drawer) and the signed-in person's name in the centre, drawn with the shimmer effect (for example the owner's name in capitals). Standalone screens replace it with their own bar: a back arrow on the left and the screen title (for example SETTINGS, DAILY LEDGER).

### 4.5 Drawer (sidebar)

A left panel with a blurred backdrop. Tap outside to close.

| Group | Items (in order) |
| --- | --- |
| Header | GULL AND ZUBAIR |
| NAVIGATION | Overview, Production, Sales, Calculator, Factory, Payments, Rep Sales |
| DAILY WORK (role) | Approvals, Daily Ledger, Stock Transfer, Payment Transfer |
| RECORDS (role) | Add Entity, Add Raw Materials, Formula Store |
| Bottom | Settings, **Logout** (red). Logout opens a confirmation dialog. |

Each item has a small line icon. The active tab is highlighted with a green pill. Which items are visible depends on the account role.

## 5. Standalone screens at a glance

Standalone screens cover the whole app and have a back arrow. The twenty-four screens are:

| # | Screen | Opened from |
| --- | --- | --- |
| 1 | Settings | Drawer |
| 2 | Approvals | Drawer |
| 3 | Daily Ledger | Drawer |
| 4 | Stock Transfer | Drawer |
| 5 | Payment Transfer | Drawer |
| 6 | Add Entity | Drawer, Payments |
| 7 | Add Raw Material (Purchase Entry) | Drawer, Factory |
| 8 | Formula Store | Drawer |
| 9 | Formula edit | Formula Store |
| 10 | Batching | Formula Store action menu |
| 11 | Batch Correction | Formula Store action menu |
| 12 | Backup & Restore | Settings |
| 13 | Recycle Bin | Settings |
| 14 | Database Structure | Settings |
| 15 | Sound & Haptics | Settings |
| 16 | App Accounts | Settings |
| 17 | Store Manager | Settings |
| 18 | Close Financial Year | Settings |
| 19 | Customer details (customer management) | Sales customer list |
| 20 | Customer edit | Customer details, Sales |
| 21 | Rep customer details | Rep Sales list |
| 22 | Rep customer edit | Rep customer details, Rep Sales |
| 23 | Entity details | Payments list |
| 24 | Expense details | Payments list |

## 6. Main tab: Overview

**Purpose.** A read-mostly dashboard of the whole business for the last 3, 6 or 12 months. It is built entirely by code, so there are no static fields.

**Layout, top to bottom.**

1. **Header row.** Title *Overview* (shimmer), a small line *Updated hh:mm*, and a range toggle with **3M**, **6M**, **12M** (default 6M).
2. **KPI grid.** Eight tiles in two columns. Each tile has a label, a big value, a sparkline wave and a ▲ or ▼ percentage change against the previous month, plus a small sub-line.

| Tile | Sub-line shows |
| --- | --- |
| Net profit (larger, highlighted) | Margin percentage and the production value it is based on |
| Sales | Number of sales |
| Produced (kg) | Returns in kg, or cost |
| Remaining (kg) | Kg in and kg sold |
| Net cash | Cash in and cash out |
| Expenses | none |
| Owed to you | Number of unreceived sales |
| Stock | Number of materials, and how many are out of stock |

3. **"Right now" strip.** Four pills for today: Production (kg), Sales, In, Out.
4. **Panel tabs.** Six pill buttons: **Sales, Production, Money, Stock, Checks, Activity**. *Checks* carries a red or amber count badge (for example 48) of data errors plus warnings. The chosen panel is remembered between visits.

**Panel contents.**

| Panel | Cards it shows |
| --- | --- |
| Sales | *Money trends* line chart with six legend chips (Sales, Net profit, Sales profit, Cash in, Cash out, Expenses); *Net profit by month* columns; *Sales by customer over time* and *Sales by store over time*; *Stock movement (kg)*; *Sold by customer (kg)*; **Who owes you** (tiles Total owed, Up to 30 days, 31 to 60 days, Over 60 days, then a ranked list with the age of the oldest sale); *Top customers*; *Sales by store*; *Cash and credit sales* donut |
| Production | *Production (kg)* line chart; production by formula and by store over time; *Factory output (units)*; ranked bars for formula, store, factory output and *Formula cost per kg* (linked formulas are marked) |
| Money | *Cash in and out* chart; *Expenses by category* ranked bars |
| Stock | *Stock by value* ranked bars; *Out of stock* as red chips |
| Checks | One card *Profit and data check* with a status chip (*N errors*, *N warnings* or *Clean*), plain-language profit notes, a *Profit by month* chart, *Loss by customer* and *Loss by store*, a **Biggest losses** list, and the audit findings |
| Activity | *Recent activity* list and a *Monthly summary* table with columns Month, Produced kg, Sales, Net profit, Sales profit, In, Out, Expenses |

**Interactions.**

- Touching a chart shows a tooltip with the values of that month.
- In **Checks**, tapping a finding or a loss row opens a **fix dialog**: a header (type, date, id), a bullet list of reasons, a note, an *Update* section with fields (text, number, date, a *Choose...* dropdown or a checkbox, depending on the problem) and an error line. This lets the owner correct a faulty record without leaving the Overview.
- Figures marked as money are hidden for restricted roles.

## 7. Main tab: Production (titled "Manufacturing")

**Purpose.** Record each batch of finished product made in a store, and review production by store, period and formula.

**Header.** Title *Manufacturing* (green shimmer) and a two-button toggle **Combined** and **Store Entry**. Store Entry is the default.

### 7.1 Store Entry view: card "New Entry"

| Order | Label | ID | Control | Notes |
| --- | --- | --- | --- | --- |
| 1 | Formula Units Available | `currentUnitsAvailable` | Status panel | Text such as *3 units available · STANDARD*. Amber when units exist, red when *0 units available*. Shows *Calculating...* while loading. |
| 2 | Select Date | `sys-date` | Date picker | Defaults to today |
| 3 | Gross Weight (kg) | `gross-wt` | Number, step 0.001 | Required |
| 4 | Container Weight (kg) | `cont-wt` | Number, step 0.001 | Weight of the box or bag |
| 5 | Net Production (kg) | `net-wt` | Read-only number with dashed border | Gross minus container, calculated |
| 6 | Dynamic Cost Value | `display-cost-value` | Red text under Net Production | Updates while typing |
| 7 | Select Store | `storeSelector` (hidden) | Toggle group (dynamic) | One button per store: ZUBAIR, MAHMOOD, ASAAN. Each store is tied to a formula. |
| 8 | Formula Units Used | `formula-units` | Number, default 1, step 0.001 | Hint: *Units of factory mixture consumed* |
| 9 | Sale Price (per kg) | `production-sale-price-display` | Large green text, for example *400/kg* | Read-only, comes from the store |
| 10 | Photo | `prod-photo-btn` | Camera icon button with a dot when photos are attached | Opens the camera. A small **×** button (`prod-photo-clear`) removes the attached photos. |

**Formula cost panel** (`prod-formula-cost-block`, three lines): Formula Unit Cost (per unit), Total Formula Cost, Cost per kg. All red.

**Result box** (`prod-profit-block`): Dynamic Cost Price per kg (red), Sale Price per kg (green), **Profit (per kg)** (emerald).

**Warning callout** (`insufficientUnitsWarning`, amber, hidden until needed): *Insufficient formula units available! Please reduce formula units used.*

**Button.** **Save Production** (`btn-save-production`, full-width gradient).

### 7.2 Combined view

The entry card is replaced by:

1. **All Stores Overview** card: period toggle *Daily, Weekly, Monthly, Yearly, All Times* and a grid (`all-stores-grid`) with one report card per store showing weight, value, cost and profit.
2. **Compare Stores By** card: toggle *Weight (kg), Total Value, Total Cost, Net Profit* and one bar chart (`storeComparisonChart`).

### 7.3 Below both views

- **Analytics** card: toggle *Weekly, Monthly, Yearly, All Times*, then a bar chart (`mfgBarChart`) and a pie chart (`mfgPieChart`).
- **Production History** (`prodHistoryList`): accordion by year, month and day. The year row shows total kg (for example *55 kg*) with a chevron. An empty selection shows *No records found for this selection.* Opening an entry reveals metric tiles, the formula used, attached photos, and edit and delete actions.

## 8. Main tab: Sales

**Purpose.** Record customer sales and customer payments (collections), and review customers, credit and sales performance.

### 8.1 Card "New Sale"

A small chip *ID: —* (`new-sale-id-display`) appears when an existing record is being edited.

**Mode toggle:** **Sale** (default) and **Collection**. The fields below change with the mode.

| Label | ID | Control | Shown in | Notes |
| --- | --- | --- | --- | --- |
| Date | `cust-date` | Date picker | Both |  |
| Customer Name | `cust-name` | Text with search dropdown | Both | Placeholder *Search or type new...*. Typing a new name shows the field below. |
| Mobile number | `new-cust-phone` | Phone, placeholder `03XX-XXXXXXX` | When the name is new | Phone is checked for 7 to 15 digits |
| Sales Representative (For Calculator Link) | `sales-rep-value` (hidden) | Toggle group (dynamic) | Sale | **Direct** plus one button per sales representative. Links the sale to a representative's Calculator. |
| Quantity (kg) | `cust-quantity` | Number, step 0.001 | Sale |  |
| Supply | `supply-store-value` (hidden) | Toggle group (dynamic) | Sale | One button per store, for example ZUBAIR, MAHMOOD, ASAAN |
| Payment | `payment-type-value` (hidden) | Toggle **Cash** and **Credit** | Sale | Default Credit |
| Add cash sale to Daily Ledger (Cash In +) | `saleAddToLedger` | Checkbox | Sale with Cash | Appears only for cash |
| Amount Received (₨) | `cust-amount-collected` | Number, placeholder *Cash received from customer* | Collection |  |
| Expired / Damage Qty (kg) | `cust-expired-qty` | Number, min 0 | Collection | Stock that was returned expired |
| Add to Daily Ledger | `custAddToLedger` | Checkbox | Collection |  |
| Expired / Damage Qty (kg) and Returned Qty (kg) | `rep-return-expired`, `rep-return-qty` | Two number fields | A separate return panel, hidden by default | Used when stock comes back from a representative-linked customer |

**Customer info box** (`customer-info-display`, shown after a customer is chosen): *Current Credit* (amber) and *Total Quantity*.

**Result card** (`sale-result-card`): Total Cost (red), Total Value (accent), Profit (emerald). Under it a **stock tile** (`inventory-warning`) shows a green dot, *In Stock: N kg*, a badge such as *Ready to sell*, a chevron that expands details, and a thin level bar.

**Collection result** (`cust-coll-result`): *Balance After Collection:* with the new balance.

**Button.** **Save Transaction** (`btn-save-cust-transaction`).

If the amount received is higher than the customer's credit, a confirmation dialog warns about over-collection and says to proceed only if it is an advance payment.

### 8.2 Card "CUSTOMERS"

- **Search customers...** field (`customer-filter`), a count text such as *30 active*, a **PDF** icon button (exports the customer list) and an **Add customer** icon button.
- **Record list** (`customers-table-body`): each row has a coloured initials avatar (colour depends on the name), the customer name in capitals, the phone number as a green underlined link, and the balance on the right (amber or red). Tapping the phone number calls; pressing and holding opens WhatsApp. Tapping the row opens the customer details screen.
- **Footer cells:** *Outstanding credit* and *Quantity sold*.

### 8.3 Analytics and Summary

- **Analytics:** toggle *Weekly, Monthly, Yearly, All Times* with two charts: sales (`custSalesChart`) and payment split (`custPaymentChart`).
- **Summary:** toggle *Daily, Weekly, Monthly, Yearly, All Time* above one summary card whose title follows the choice (*Daily Sales*, and so on). It contains a margin chip, the hero **Total value**, a cash-versus-credit split bar, and four tiles: **Quantity**, **Net profit**, **Cash (incl. received)**, **Pending credits**.
- **Sales History** (`custHistoryList`): accordion by year, month and day. A month row reads like *NOV 2026 · 4,295 kg sold* with sold and received totals. Entries show status chips (PENDING, PAID) and an **Invoice** share button.

### 8.4 Customer details screen (opened from the list)

Top bar: round photo avatar (or initials), customer name, phone link, and a three-dot menu with **Edit customer** and **Statement PDF**.

| Part | Description |
| --- | --- |
| Balance card | *Current debt* (large, amber), two tiles *TOTAL SALE* and *RECEIVED*, a progress bar and a sentence such as *58% of the amount owed has been received* |
| Search | *Search history...* field |
| **Money Out (Cash Out −)** button | Records money paid out to the customer; gradient button beside the search field |
| Filter chips | **All, Credit, Cash, Payments, Unpaid** |
| Month jump | Chips to jump to a month (shown when there are many months) |
| History | Month headers (*NOV 2026 · 816.8 kg sold · Sold … · Received …*) with day cards. Each card shows the day number and weekday, a type (*Credit sale*, *Money out*, *Collection*), a status chip, quantity and price, the amount, the running balance beneath it, and an **Invoice** button on sales. |

Tapping a card opens the **transaction sheet** from the bottom.

### 8.5 Customer edit screen ("ADD CUSTOMER" or "Edit Customer")

| Section | Label | ID | Control |
| --- | --- | --- | --- |
| Identity | Photo | `cust-photo-preview` | Round avatar, green **+** button to upload, **Camera** chip, **Remove** chip (when a photo exists). Tapping the avatar opens the photo viewer. |
| Identity | Customer Name | `edit-cust-name` | Text, placeholder *Customer name* (on a new customer *Type name to search or add...*). Hint: *Editing the name will update all records for this customer*. Max 80 characters. |
| Contact | Phone number | `edit-cust-phone` | Phone, `03XX-XXXXXXX` |
| Contact | Address / location | `edit-cust-address` | Text, placeholder *Area, shop no., etc.*, with a **locate** button (`fetchDeviceLocation`) that fills the address from the device GPS; a status line (`location-status`) reports the result |
| Account | Previous balance (old debt) | `edit-cust-old-debit` | `Rs` affix, number |
| Account | Custom sale price | `edit-cust-custom-price` | `Rs` and `/kg` affixes, placeholder *Factory default* |
| Account | Manual ledger book page | `edit-cust-ledger-page` | Text, max 12, placeholder *e.g. 45*. Hint: *Shown as a small badge on the customer avatar.* |
| History | Edit history | `cust-edit-history` | List of earlier changes (shown only when editing) |
| Actions | Delete (red, editing only) and **Add Customer** / **Update Customer** (gradient) |  |  |

## 9. Main tab: Calculator

**Purpose.** Settle accounts with each sales representative, or compare all representatives together. Settlement means: how much was sold, returned, expired, how much cash should come back, and how much commission is due.

### 9.1 Top controls (always visible)

- **Sales Representative** selector (`sellerSelectBtn`, pop-over, hidden `sellerSelect`). The first choice is **COMPARISON**, followed by one choice per representative.
- **Date Range** button (`calcRangeBtn`) showing *Select dates* or the chosen date (for example *26 Nov 2026*) with a calendar icon. It opens a range calendar pop-over. Two hidden date inputs (`sale-date-from`, `sale-date`) hold the values.

### 9.2 Comparison mode (selector = COMPARISON)

| Element | Detail |
| --- | --- |
| Title and period toggle | *Comparison*; **Week, Month, Year, All Time** (default All Time) |
| View Chart For | Pop-over with **Net Profit (Value)**, **Gross Revenue (Value)**, **Quantity Sold** |
| COMPETITION | Chart comparing representatives. Shows *No data yet* when empty. |
| MARKET COMPOSITION | Share chart. Shows *Nothing recorded in this period* when empty. |
| Metric table | Rows: Qty Sold, Returns, Expired (→ CHORA), Shared (Deduction), Total Cost, Gross Revenue, **Net Profit** (highlighted gold), Credit Issued, Credit Recovered, Field Expenses, Commission Paid. One column per representative. |

### 9.3 Individual mode (one representative chosen): entry card

**Product Quantities**

| Label | ID | Control | Notes |
| --- | --- | --- | --- |
| Total Sold Qty | `totalSold` | Read-only number | Filled from the Sales tab. Triple-tapping it opens the underlying sales. |
| Returned Qty | `returnedQuantity` | Read-only | From representative returns |
| Expired / Damage Qty | `expiredQuantity` | Read-only |  |
| Shared (Deduction) | `sharedQty` | Number | The only editable quantity here |
| Net Sold Qty | `netSoldQty` | Read-only | Calculated |
| Return Stock To Physical Inventory | `ret-store-*` | Toggle of stores | Shown only when stock was returned. Hint: *This will increase the store's stock level without creating a new production record.* |
| Expired Stock | `expiredSection` | Information box | *Expired Stock — Added to CHORA Raw Material* with an explanation |
| Credit Sales Qty | `creditSales` | Number | Triple-tap opens the credit sales |

**Commission**

- *Commission / Unit* (`commissionPerUnit`): number.
- *Commission Paid (Advance/Paid)* (`commissionPaid`): read-only field that says *Tap to add commission*. Tapping opens the **Commission Paid pop-up** (section 10.8).

**Collections & Handover Cash**

- *Recovered Credit* (`prevCreditReceived`): number, triple-tap opens source records.
- *Field Expenses* (`fieldExpenses`): read-only, *Tap to add expenses*. Opens the **Field Expenses pop-up**.
- *Physical Cash Received from Rep* (`receivedCash`): number.

**Result card** (`calc-result-card`): *Expected Cash*, **Status** (OK, Short or Over, coloured), *Gross Commission Earned*, *Commission Paid*, *Commission Payable*.

**Checkbox:** *Add cash received, expenses and commission to Daily Ledger* (`calcAddToLedger`).

**Button.** **Save Transaction**. If the cash differs from the expected amount a dialog titled *Settlement* asks *Cash is short / over by X. Save anyway?*

### 9.4 Individual mode: reports

1. **Performance Analytics for …** with a period toggle *Weekly, Monthly, Yearly, All Times*, a metric pop-over (**Weight (kg), Total Value, Total Cost, Net Profit, Cash Sales, Credit Sales**) and a chart.
2. **Performance Overview (…)** with a toggle *Daily, Weekly, Monthly, Yearly, All Time*. The report card shows a profit line, a cash-versus-credit bar and tile groups:
   - **Quantities:** TOTAL SOLD, RETURNED, EXPIRED (TO CHORA), SHARED (DEDUCTION)
   - **CREDIT:** a bar for *Out* and *Recovered*, tile NET DEBT
   - **CASH HANDOVER:** a status chip such as *Short 6,900*, tiles EXPECTED, RECEIVED, FIELD EXPENSES, COMMISSION PAID
   - Two collapsible links: *Expenses breakdown* and *Commission breakdown*
3. **Market debt tracker:** *Current market debt* (large red), a bar for Recovered and Outstanding, tiles *Total credit issued* and *Cash recovered*.
4. **Calculator History** (`historyList`): accordion by year, month and day. Day entries read like *17 NOV 2026 · SHORT: 13,700 · \<representative>*, or *OVER*, or *PERFECT MATCH*, with revenue totals. Opening an entry shows the tiles above for that day.

## 10. Other main tabs, work screens and pop-ups

### 10.1 Main tab: Factory

**Purpose.** Turn raw materials into formula units (a unit is one mixed batch of a stored formula), watch raw-material stock, and see the factory's cost and profit.

**Card "New Production Entry"**

| Label | ID | Control | Notes |
| --- | --- | --- | --- |
| Date | `factory-date` | Date picker, top-right of the card |  |
| Formula | `factory-formula-value` (hidden) | Toggle group (dynamic) | One button per formula, for example STANDARD, ASAAN |
| Units to Produce | `factoryProductionUnits` | Number, default 1, minimum 1 | Whole numbers only |
| Total Cost | `factoryTotalProductionCostDisplay` | Read-only red callout | Units multiplied by cost per unit |
| Formula preview | `factoryFormulaDisplay` | Callout | Shows *Select a store...* first. After a choice it lists the formula name with its unit count, each ingredient with its weight and cost, and an *Additional Cost* line in red. |

Button: **Save Factory Production**.

**Card "Units Available"** (with a green dot that flashes when data refreshes) has its own formula toggle (`factoryAvailToggle`) and four stat groups:

| Group | Tiles |
| --- | --- |
| UNITS | Available, Used |
| OUTPUT | Quantity (kg), Raw Used (kg) |
| COST | Per Unit, Total, Materials |
| PROFIT | Total, Per kg |

**Section "Raw Material Inventory"**

- *Sort by* toggle: **Material** and **Total Value**. The active button shows an arrow (▲ or ▼); the inactive one shows ⇅.
- Record list (`factoryInventoryTableBody`): material name in capitals, a line such as *0.2 Bag (9.84 kg)*, *Collected 104.68 kg*, and the total value on the right. Tapping a row opens the Add Raw Material screen on that material.
- Footer cells: *Total collected weight* and *Total inventory value*.

**Section "Performance Summary"**

- Toggle *Daily, Weekly, Monthly, Yearly, All Times*.
- Hero **Total profit** with the caption *average per kg*.
- Groups: **Units** (Available, Used, *Cost / day* with *average extra*), **Output** (Quantity, Raw used, and a collapsible *MATERIALS BREAKDOWN* with a count badge), **Cost** (Per unit, Total, Materials).

**Section "Recent Production Activity"** (`factoryHistoryList`): accordion by year and month with entry count and cost, for example *NOV 2026 · 7 entries · Cost 1,72,578.64*.

### 10.2 Main tab: Payments

**Purpose.** Record money paid out, money received and business expenses, and see the financial position of the business.

**Card "Transaction Manager"**

| Label | ID | Control | Notes |
| --- | --- | --- | --- |
| (chip) | `expense-id-display` | *ID: —* chip | Appears when editing |
| Clear |  | Outlined button, top-right | Resets the form |
| Date | `expenseDate` | Date picker |  |
| Name/Entity | `expenseName` | Text with search dropdown, placeholder *Search expenses or entities...* | Matches saved expense names and entities |
| Amount | `expenseAmount` | Number, min 0, step 0.01 |  |
| Description *(Optional)* | `expenseDescription` | Text, placeholder *Add details...* |  |
| Photo | `expense-photo-btn` | Camera icon with a dot when a photo is attached |  |
| Transaction Type |  | Toggle with three buttons | **Operating Expense**, **Payment IN** *(Cash In +)*, **Payment OUT** *(Cash Out −)*. Payment OUT is the default. |
| Add to Daily Ledger | `expenseAddToLedger` | Checkbox |  |

Button: **Save Transaction**.

**Card "ECONOMIC HEALTH DASHBOARD"** (hidden from restricted roles). One long card of label and value lines:

| Block | Lines |
| --- | --- |
| Header | Big **net cash** figure; *Operating Cash Flow* |
| CASH IN HAND DETAILS | Net Sales Cash (Direct Customers), Net Production Cash, Representative Collections, External Payments Received, External Payments Made (red), Operating Expenses (red), **NET CASH IN HAND** |
| CURRENT ASSETS | Cash in Hand, Raw Materials Inventory, Formula Units Inventory |
| ACCOUNTS RECEIVABLE | Direct Customer Credits, Representative Credits, **TOTAL RECEIVABLES**; then **TOTAL CURRENT ASSETS** |
| CURRENT LIABILITIES | Supplier Payables (Raw Material Invoices), Entity Payables (Payment Entities), **TOTAL ACCOUNTS PAYABLE**; then **TOTAL CURRENT LIABILITIES** |
| Result lines | Net Working Capital, Total Enterprise Value |
| FINANCIAL RATIOS | Current Ratio, Quick Ratio, Cash Ratio (show — when they cannot be worked out) |

**Card "CASH IN HAND TRACKER"**: period toggle *Daily, Weekly, Monthly, Yearly, All Times*, then **Net Cash Flow** (Production Value, Sales Tab Cash, Calculator Cash, Payments In, Payments Out, Expenses, **NET CASH**) and **TOTAL OUTSTANDING CREDITS** (Sales Tab Credits, Calculator Credits, **TOTAL CREDITS**, caption *Credits awaiting collection*). Values are written as `Rs 0`.

**Card "Unified Records"** (`payments-unified-section`)

- Search field (*Search...*), **PDF** icon button, two pop-over selectors.
- View mode: **Entities & Balances** or **Expenses**.
- Period: **Today, This Week, This Month** (default), **All Time**.
- Record list with a footer *Total*, and a summary strip with *Total Receivable, Expenses, Supplies Payable, Entity Payable*.
- Tapping an entity opens Entity details; tapping an expense name opens Expense details (section 14).

**Section "Payment History"** (`paymentHistoryList`): accordion by year, month and day. Headers read like *NOV 2026 · 2 entries · In 3,50,000 · Out 0*, and day rows show *In* and *Out*.

### 10.3 Main tab: Rep Sales

**Purpose.** The screen a field representative uses to record sales and collections on the road. The administrator also sees a monitoring view.

**Card "New Transaction"** has a **Sale** / **Collection** toggle.

| Label | ID | Control | Shown in |
| --- | --- | --- | --- |
| Date | `rep-date` | Date picker | Both |
| Customer Name | `rep-cust-name` | Text with dropdown, placeholder *Search customer...* | Both |
| Mobile number | `rep-new-cust-phone` | Phone, `03XX-XXXXXXX` | New customer |
| Quantity (kg) | `rep-quantity` | Number, step 0.001 | Sale |
| Payment Type | `rep-payment-value` (hidden) | Toggle **Credit** (default) and **Cash** | Sale |
| Amount Received (₨) | `rep-amount-collected` | Number, placeholder *Cash received from customer* | Collection |
| Expired / Damage Qty (kg) | `rep-expired-qty` | Number, min 0 | Collection |

Info box (`rep-customer-info-display`): *Current Debt*. Result line: *Transaction Value:* (the label changes in collection mode) with the figure in green. Button: **Save Transaction**.

**Admin View card (role: admin)**: *View For:* representative pop-over (first choice —), *Select Date*, a **map** (`rep-map-container`, Leaflet with OpenStreetMap tiles, zoom + and − buttons) and a legend *Cash Sale | Collection | Credit Sale* with a coloured pin for each kind. The pins are the places where the representative saved each transaction. When map tiles cannot be loaded (offline) the map shows a plain green area.

**Analytics card (role: admin)**: toggle *Daily, Weekly, Monthly, Yearly, All Time*, **Money in** hero, a split bar and tiles *Collections, Cash sales, Credit sales*.

**Customer List card**: search (*Search customers...*), PDF button, add button, a record list (empty text *No customers yet*) and a footer of three cells: *Outstanding credit, Credit sales, Collections*.

**History** (`repHistoryList`): accordion of the representative's transactions.

The rep customer screens (details and edit) match the Sales ones in section 8, except that the edit screen has no *Custom sale price*, the details screen has no *Money Out* button, and the photo hint says *Shown as a small badge on the customer's photo.*

### 10.4 Daily Ledger (cash book)

**Title:** DAILY LEDGER.

**Top card "Cash In Hand"**: a **PDF export** icon button (`dl-pdf-btn`, gradient square, top-right), the hero total (`dl-hero-amt`) and three sub-balances *Cash, Bank, Others* (one for each payment channel group).

**Summary tiles:** *Total Cash In* (green, with *N entries*), *Total Cash Out* (red, with *N entries*), *Net Balance* (with a tag *Surplus* or deficit).

**Entry form card** (title *Add Entry*, becoming *Transfer Between Channels* or *Edit Entry*):

| Label | ID | Control | Notes |
| --- | --- | --- | --- |
| Entry type |  | Three wide buttons **+ In** (green), **− Out** (red), **Transfer** (blue) | The active one is filled |
| Date | `dl-date` | Date picker |  |
| Amount | `dl-amount` | Text with decimal keypad, placeholder 0.00 | Strict number, up to 10,000,000,000 |
| Payment Channel / Mode (*From Channel (Source)* in a transfer) | `dl-mode` | Text, max 40, placeholder *e.g. Cash, Bank Transfer, Easypaisa...* | Dropdown of earlier channels |
| To Channel (Destination) | `dl-to-mode` | Text, max 40 | Transfer only |
| Party / Customer / Vendor (optional) | `dl-party` | Text, max 80, placeholder *e.g. Ali Store* | Hidden for transfers |
| Bill / Ref # | `dl-ref` | Text, max 40, placeholder *Optional* |  |
| Remarks / Note | `dl-note` | Text, max 120, placeholder *Optional* |  |

Buttons: **Cancel Edit** (only when editing) and the save button whose label follows the type: **Add Cash In**, **Add Cash Out** or **Record Transfer**.

**Further blocks:** *Payment Channels* (a card listing each channel with its signed balance, for example *Cash + 5,19,250*), *Category Summary* (a card of spending categories), and *Cashbook Log & History* with a search field (*Search by party, category, mode, amount...*) and an accordion list by year, month and day.

### 10.5 Stock Transfer

**Title:** Stock Transfer. **Group label:** *Move Stock Between Stores*.

| Label | ID | Control |
| --- | --- | --- |
| From Store | `stockTransferFromBtn` | Pop-over, shows *Select Store* until chosen |
| To Store | `stockTransferToBtn` | Pop-over |
| Date | `stock-transfer-date` | Date picker |
| Quantity (kg) | `stock-transfer-qty` | Number, placeholder 0.00, min 0, step 0.01 |
| Note (optional) | `stock-transfer-note` | Text, max 120, placeholder *e.g. Balancing stock ahead of weekend rush* |
| Availability line | `stockTransferAvailability` | Text showing how much the source store has |

Button: **Transfer Stock**. Below: *Transfer History* (accordion, for example *2026 · 69 kg*) and a note box explaining that a transfer is only allowed up to the stock currently available at the source store.

### 10.6 Payment Transfer

**Title:** Payment Transfer. **Group label:** *Move Balance Between Entities*.

| Label | ID | Control |
| --- | --- | --- |
| From Entity | `payment-transfer-from-search` | Text with dropdown, placeholder *Search entity...* |
| To Entity | `payment-transfer-to-search` | Same |
| Date | `payment-transfer-date` | Date picker |
| Amount | `payment-transfer-amount` | Number, min 0, step 0.01 |
| Note (optional) | `payment-transfer-note` | Text, max 120, placeholder *e.g. Settling shared supplier bill* |
| Photo | `payment-transfer-photo-btn` | Camera icon with a dot |

Button: **Transfer Payment**. Below: *Transfer History* and a note box saying the transfer records an OUT for the source entity and an IN for the destination entity. The source entity needs at least that balance.

### 10.7 Approvals

Used when staff submit changes that the administrator must approve.

- **Hero card** (`approvals-hero`): *Loading approvals…*, then either a green *All caught up* or a count badge with *waiting for approval* and a sub-line. A small number badge also appears on the drawer item.
- **Search** (`approvals-search`): *Search by name, item or amount*.
- **Type chips** (`approvals-types`): All, Sales, Payments, Expenses, Production, Factory, Stock, Customers, Other, each with a count. Hidden when empty.
- **Person chips** (`approvals-people`): one chip per submitting person.
- **List** (`approvals-list`): one row per request, headed by the person's name or the request type, with an amount, summary lines and the kind (*New*, *Change* or *Delete*, for example *Delete sales 1a2b3c4d*). Each row has **Approve** and **Discard**. Bulk buttons read **Approve all (N)** and **Discard all (N)**. An empty filter shows *No matches — Nothing matches your search or filter.*
- The submitting person later receives a notification: *Approved* or *Discarded by admin*.

### 10.8 Pop-ups used in the Calculator

Both are dialogs with the standard glass look (section 3.4), opened by tapping a read-only field.

|  | Field Expenses | Commission Paid |
| --- | --- | --- |
| Chip | *Expenses* | *Commission* |
| Caption | *Search or type a category, tap it, add the amount and save. The field shows the total paid.* | Same |
| Category field | Placeholder *Search or type a category (e.g. Fuel)*, max 60, with a dropdown of earlier categories | Placeholder *Search or type a category (e.g. Advance)* |
| Amount field | Number | Number |
| List | Added rows, each removable, and a line *Total paid* | Same |
| Buttons | **Done** (ghost) and **Save** (solid) | Same |

New category names are remembered and offered next time. The total is copied back into the read-only field on the Calculator.

## 11. Roles and what each person sees

Each account holds one or more of six roles. The role decides which tabs and drawer groups appear and whether money figures are shown. The screenshots were captured with an administrator account, so role differences below come from the code, not from pictures.

| Role | What the interface shows |
| --- | --- |
| **Admin** | Every tab, every drawer group, Settings, App Accounts, all money figures, Admin View and Analytics on Rep Sales |
| **Rep** | Only the Rep Sales tab: new transaction, own customer list and own history |
| **Production** | Only the Production tab, entry only |
| **Factory** | Only the Factory tab, entry only |
| **Payments** | Only the Payments tab, to enter transactions. Payment history and money figures stay hidden. |
| **Sales** | Only the Sales tab. Money figures stay hidden. |

Tabs for non-admin roles appear in this order: Production, Factory, Sales, Payments, Rep. Money values carry a privacy marker so they can be hidden in one place across the whole app. Changes made by staff can be sent to the administrator as requests, which appear in **Approvals** (section 10.7).

## 12. Records and formula screens

### 12.1 Add Entity

An *entity* is a person or company that money is paid to or received from (supplier, payee, partner). The title reads *Add Entity* and changes when editing.

| Card | Label | ID | Control and notes |
| --- | --- | --- | --- |
| Identity | Photo | `entity-photo-preview` | Round avatar; green **+** to upload, **Camera** chip, **Remove** chip once a photo exists; tapping the avatar opens the photo viewer |
| Identity | Entity Name | `entityName` | Text, placeholder *Name* |
| Contact and payment details | Phone number | `entityPhone` | Phone, `03XX-XXXXXXX` |
| Contact and payment details | Wallet / account details | `entityWallet` | Text, placeholder *JazzCash, Easypaisa or bank account* |
| Contact and payment details | Manual ledger book page | `entityLedgerPage` | Text, max 12, placeholder *e.g. 45*. Hint: *Shown as a small badge on the entity's photo.* |
| Account | Old balance (already credit or debit) | `entityOpeningType` | Dropdown: *Credit — we owe them (payable)* or *Debit — they owe us (receivable)* |
| Account | Old balance amount | `entityOpeningAmount` | `Rs` affix, number, min 0. Hint: *Balance carried over from before this app. Leave 0 for none.* |
| Edit history |  | `entity-edit-history` | List of earlier changes, shown only when editing |

Buttons: **Delete** (red, editing only) and **Save Entity** (gradient).

### 12.2 Add Raw Material (title "Purchase Entry" or "Add Raw Material")

Records a purchase of a raw material and shows that material's stock.

| Card | Label | ID | Control and notes |
| --- | --- | --- | --- |
| Material | Search or add material | `factoryMaterialName` | Text with box icon, placeholder *Search saved materials or type a new one*, with a dropdown |
| Material | Date | `factoryPurchaseDate` | Date picker |
| Material | Summary callout | `factoryMaterialSummary` | Three cells: **Collected**, **In stock**, **Pending to suppliers** |
| New purchase | Unit name | `factoryMaterialUnitName` | Text, placeholder *bag, box, carton* |
| New purchase | Units bought | `factoryMaterialQuantity` | Number, placeholder 0 |
| New purchase | Weight per unit | `factoryMaterialConversionFactor` | Number, default 1, `kg` affix at the right |
| New purchase | Cost per unit | `factoryMaterialCost` | `Rs` affix, placeholder 0.00 |
| New purchase | Calculated tiles |  | **Stock** (kg), **Cost per kg**, **Total amount** |
| Supplier | Supplier mode | `factorySupplierSeg` | Toggle **None**, **Existing**, **New** |
| Supplier (Existing) | Select supplier | `factoryExistingSupplier` | Text with dropdown, placeholder *Search or select supplier...* |
| Supplier (New) | Supplier name | `factorySupplierName` | Text, placeholder *Company or person* |
| Supplier (New) | Phone | `factorySupplierPhone` | Phone, placeholder *Phone number* |
| Supplier (New) | Checkbox | `factorySupplierAutoEntity` | Ticked and locked: *Also add to Quick Entity Selection as a **PAYEE*** |
| History | Purchase history | `factoryPurchaseHistory` | Accordion of earlier purchases (for example *1 entry · Purchased 80,052*) |

A fixed bottom bar holds **Delete Material** (red-orange, only for an existing material) and **Save Purchase** (gradient) side by side.

### 12.3 Formula Store

- **List** (`formulaStoreList`): one card per formula. The title reads like *STANDARD (1 UNIT)*. Under it: each ingredient with its weight (kg) and cost; sub-formulas shown in bold with their own indented ingredients marked ↳; *Additional Cost (750 per unit)* in red; then four summary lines *Unit Weight, Raw Material Cost per Unit, **Total Cost per Unit**, Cost per kg (Sales/Calc)*. The card footer says *Tap to edit*, *Used by ZUBAIR, MAHMOOD* or *Linked in STANDARD, ASAAN*.
- **Floating action button** (round **+** at the bottom right, `formulaFabDial`): opens a speed-dial of three labelled mini buttons: **Correction** (Batch Correction), **Batching**, **Formula** (new formula).

### 12.4 Formula edit (title "Edit Formula")

| Label | ID | Control and notes |
| --- | --- | --- |
| Formula Name | `fs-edit-name` | Text, max 60, placeholder *e.g. Premium Blend* |
| Additional Cost (Per Unit) | `fs-additional-cost` | Number, default 0 |
| Cost Adjustment for Sales & Calc (X) | `fs-cost-factor` | Number, default 1 |
| Ingredient table | `fsEditContainer` | Header *Material, Cost / Unit, Qty (kg)*. Each row has a material search (or a link to another formula), a cost display, a quantity field and a remove button. |
| + Add Ingredient Row |  | Outlined button |
| Hint |  | *Tip: search a material, or link a Formula so its materials follow any later changes to it.* |
| Summary |  | *Unit Weight*, *Raw Material Cost per Unit*, *Total Cost per Unit*, *Cost per kg (Sales/Calc)* |

Buttons: **Delete Formula** (red, editing only) and **Save Formula**.

### 12.5 Batching (mixing calculator)

Tells the mixer how much of each ingredient to weigh.

| Label | ID | Control and notes |
| --- | --- | --- |
| Formula | `bm-formula-btn` | Pop-over button *Select formula*; a small info line (`bmFormulaInfo`) describes it |
| Unit |  | Toggle **g** and **kg** (default kg) |
| Mode |  | Toggle **Weight** (default), **Units**, **Ingredient** |
| Ingredient | `bm-ing-btn` | Pop-over *Select ingredient*, shown only in Ingredient mode |
| Value | `bm-value` | Number. Its label follows the mode, for example *Total Batch Weight (kg)*. |
| Result | `bmMakeResult` | List of amounts to weigh for each ingredient |
| Name (optional) | `bm-save-name` | Text, max 60, placeholder *e.g. Morning batch* |
| Buttons |  | **Cancel Edit** (editing only), **Save Calculation** |
| Saved Calculations | `bmSaved` | History list with a count |

### 12.6 Batch Correction

Fixes a batch that was weighed wrongly or partly used.

| Label | ID | Control and notes |
| --- | --- | --- |
| Formula | `bf-formula-btn` | Pop-over *Select formula* |
| Unit |  | Toggle **g** (default) and **kg** |
| Original Batch (g) | `bf-original` | Number |
| Used / Lost (g) | `bf-used` | Number |
| Remaining Batch | `bfRemaining` | Calculated, red |
| Mixed differently than the formula? | `bfActualRows` | Collapsible group of fields for the amounts actually used |
| Required Additions | `bfRows` | Rows with an *Add* box per ingredient, plus *New total*, *Total in Remaining*, *Total Required*. Hint: *Type a new amount in any Add box or in Total to Add – all other ingredients update automatically.* |
| Total to Add (g) | `bfTotalAdd` | Number |
| Name (optional) | `bf-save-name` | Text, max 60 |
| Buttons |  | **Cancel Edit**, **Save Correction** |
| Saved Corrections | `bfSaved` | History list with a count |

Before a formula is chosen the screen shows the text *Select a formula*.

## 13. Settings and its screens

### 13.1 Settings

Bar with back arrow and **SETTINGS**. Rows sit inside five cards, each with an upper-case group label. Each row has a coloured line icon and an upper-case label.

| Group | Rows |
| --- | --- |
| Data & Sync | **Sync Data** (one-tap sync), **Backup & Restore**, **Recycle Bin** (red bin icon), **Database Structure** |
| Appearance & Device | **Theme** (label *THEME: DARK*, *AUTO* or *LIGHT*), **Sound & Haptics**, **Start with Computer: On/Off** (only in the desktop app) |
| Security | **Enable / Disable Fingerprint Lock**, **App Accounts** |
| Business | **Store Manager** |
| Danger Zone | **Close Financial Year** (red text) |

### 13.2 Backup & Restore

Two cards. Under *Backup*: **Backup to Phone**, *Save a local copy of all data to your device*. Under *Restore*: **Restore from Phone**, *Load a previously saved backup file*, which opens the phone's file picker for `.gznd` backup files.

### 13.3 Recycle Bin

- Status line, for example *0 deleted records (kept for 90 days)*.
- Filter pop-over (`recycleBinFilterBtn`): **All Tabs, Sales Tab, Rep Tab, Manufacturing Tab, Calculator Tab, Factory Tab, Payments Tab, Daily Ledger, Formula Store**.
- **Empty Bin** button (orange-red with a bin icon).
- Information box: *Restore puts the record back to its original collection. Records expire after **90 days**. Use **Delete Forever** to erase a record immediately and permanently.*
- List (`recycleBinList`) of deleted records, each restorable or erasable. Empty state: *Recycle Bin is empty. Deleted transactions will appear here and can be restored within 90 days.*

### 13.4 Database Structure

Title with a **Refresh** icon button. The body shows *Loading database structure…* and then the list of stored collections with their sync details. (No screenshot shows the loaded state.)

### 13.5 Sound & Haptics

Two blocks, each with a heading, a one-line description, a test button and a selector card.

| Block | Test button | Choices in the selector card |
| --- | --- | --- |
| Haptic Feedback Intensity | **Test** | Disabled, Light, Medium (default, *Balanced standard tactile click*), Strong, Extreme |
| Sound Feedback Intensity | **Play** | Muted, Soft (Discreet), Standard (Crisp) (default), Loud (Resonant), Extreme Boost (Maximum) |

The selector card shows the current title and description with a chevron and opens a list. A status line under each block reports the result of the test. Footer note: *These settings apply to this device only and cover dialogs, switches and toggle buttons.*

### 13.6 App Accounts (administrator)

| Part | Label | ID | Control |
| --- | --- | --- | --- |
| Add Account | Email | `acct-new-email` | Email, placeholder *user@example.com* |
| Add Account | Password | `acct-new-password` | Password, placeholder *min 8 characters* |
| Add Account | Name | `acct-new-name` | Text, placeholder *full name* |
| Add Account | Roles | `acct-role-*` | Six toggle buttons that can be combined: **Admin, Rep, Production, Factory, Payments, Sales** |
| Add Account | Button | `acct-add-btn` | **Add Account** |
| Registered Accounts | **↻ Refresh** |  | Reloads the list (`manage-accounts-list`, first showing *Loading...*) with one row per account |

### 13.7 Store Manager

| Label | ID | Control and notes |
| --- | --- | --- |
| Heading | `store-form-heading` | *New Store*, or the name being edited |
| Store Name | `store-name-input` | Text, max 32, placeholder *e.g. KARACHI* |
| Formula | `store-formula-btn` | Pop-over *Select formula* |
| Sale Price (per kg) | `store-sale-price-input` | Number, default 0, min 0, step 0.01 |
| Buttons |  | **Cancel** (editing only) and **Add Store** or **Update** |
| Existing Stores | `store-list-container` | One row per store with edit and delete actions |
| Note |  | *A store's key is generated from its name when the store is created (ZUBAIR becomes `zubair`).* It is not changed afterwards. |

Stores created here appear as toggle buttons on Production, Sales, Factory and the Stock Transfer screen.

### 13.8 Close Financial Year

A full-screen, red-themed flow for year-end.

1. **Header band:** icon, title **CLOSE FINANCIAL YEAR**, a phase badge (*PREVIEW*) and the sub-title *Compact all records into opening balances*.
2. **Preview grid** (`cy-preview-grid`): rows summarising what will be compacted.
3. **Danger notice:** a red triangle icon, *Irreversible — original records will be compacted* and *Enter your account password to encrypt the year-end backup and confirm this action.*
4. **Password field** (`close-year-confirm-input`): placeholder *Account password*, eye button, an error line below, Enter submits when allowed.
5. **Buttons:** **Cancel** and **Close Financial Year**. The red button stays disabled until a password is typed.
6. **Progress block:** *Processing: \<stage>* (starting with *Initializing…*), a percentage and a progress bar.
7. **Completion block** (`close-year-complete`): shown when finished.

## 14. Detail screens

### 14.1 Entity details

Header shows the entity name. Then: a stats line (`manageEntityStats`), a summary panel (`entityManagementSummary`) with the balance, a search field *Search history...*, filter chips (`entityHistoryFilters`), month-jump chips (`entityMonthJump`) and the history list (`entityManagementHistoryList`). Its edit button opens Add Entity (section 12.1).

### 14.2 Expense details

| Part | Detail |
| --- | --- |
| Header | Expense name and a stats line, *Total: 0.00* |
| Search | *Search history...* (`expense-history-search`) |
| Range selector | A hidden dropdown (`expenseOverlayRange`): Today, This Week, This Month, This Year, All Time (default) |
| **Quick Add Expense** | *Amount (Rs)* (`quickExpenseAmount`, placeholder 0.00), *Description (optional)* (`quickExpenseDescription`, placeholder *e.g. Monthly rent*), **Save Expense**, and the hint *Adds a new expense record under \<name> for today's date.* |
| History | `expenseManagementHistoryList` |
| Danger | **Delete All Expenses** (small red button) |

### 14.3 Customer and rep-customer screens

See sections 8.4, 8.5 and 10.3.

## 15. Limits and messages the person meets

These limits are enforced by the forms and appear as warnings in dialogs or under the field.

| Area | Limit or message |
| --- | --- |
| Phone numbers | 7 to 15 digits, an optional leading `+` |
| Dates | A future date or an invalid date is refused on every save form |
| Amounts | Plain numbers only (no `1e12`, no spaces, no repeated dots). Shown and stored to two decimals. The ledger amount is capped at 10,000,000,000. |
| Names | Customer name 80 characters. Duplicate names are refused for entities, suppliers and formula ingredients (ignoring case and spaces). A new supplier with an existing name is blocked with advice to use *Existing supplier*. |
| Raw material purchase | Units 0.001 to 1,000,000; cost per unit 0.01 to 100,000,000; weight per unit 0.001 to 100,000; material name 80 and unit name 30 characters; a unit name is required when the weight per unit is not 1; a purchase needs a supplier choice if the supplier mode is Existing or New |
| Production | Gross weight required (0.001 to 1,000,000); container weight 0 or more; formula units above 0; *Insufficient formula units available!* warning |
| Sales and Rep | Quantity 0.001 to 1,000,000. Over-collection triggers a confirmation. Selling more than the stock shows the stock tile in red. |
| Calculator | Commission per unit up to 100,000; cash and expense fields up to 10,000,000,000; settlement date not in the future; *Cash is short / over by X. Save anyway?* |
| Formula editor | Additional cost 0 to 100,000,000; cost factor above 0 and at most 100; ingredient quantity 0.001 to 1,000,000 |
| Store Manager | Sale price between 0.01 and 100,000,000 |
| Transfers | Stock transfer is limited to the stock at the source store on that date; payment transfer needs enough balance in the source entity; notes up to 120 characters |
| Deletes | Confirmation dialogs in the red *Permanent* tone. Deleted records go to the Recycle Bin for 90 days. |

## 16. Appendix

### 16.1 Screenshot coverage

The supplied capture contains 304 PNG screenshots and one index file (`index.tsv`), 305 files in all. They are grouped in folders and each file name carries the label of the control that was tapped.

| Folder | Screenshots | What it shows |
| --- | --- | --- |
| sidebar | 2 | Drawer open |
| settings | 12 | Settings and each row, including the three themes |
| overview | 9 | Range toggle and each panel |
| prod | 9 | Combined, Store Entry, store choices, save |
| sales | 46 | Sale and Collection, add customer, each customer, chart periods, summary periods |
| customer-ledger | 30 | A customer's details, menu, filters, history cards, invoice |
| calc | 46 | Comparison, calendar, metric choices, periods, history, short and over days |
| factory | 37 | Entry, formulas, inventory sorting, summary periods, breakdown |
| payments | 29 | Transaction Manager, periods, entity records, history |
| rep | 18 | Rep entry, admin map, analytics, customer list |
| daily-ledger | 11 | In, Out, Transfer, channels, history |
| stock-transfer | 6 | Stores, date, transfer, history |
| payment-transfer | 3 | Form and save |
| raw-material | 5 | Existing and new material, save |
| formula-store | 11 | Formula cards and the action menu |
| formula-store-edit | 3 | Add ingredient row, save |
| batch-mix | 6 | Formula, units, ingredient, weight |
| batch-fix | 5 | Formula, units |
| store-manager | 3 | Formula picker, add store |
| app-accounts | 3 | Add and refresh |
| add-entity | 2 | Form and save |
| recycle-bin | 2 | Empty state and filter |
| backup-restore | 1 | Two cards |
| feedback | 5 | Test, level menu, play |

### 16.2 What could not be confirmed from pictures

The following were described from code only, because no screenshot of them is in the capture: splash screen, sign-in screen, fingerprint lock, Approvals, Close Financial Year, the loaded Database Structure screen, Entity details, Expense details, Rep customer edit, camera, photo viewer, and the Field Expenses and Commission Paid pop-ups. The screenshots also show an administrator account only, so what other roles see is taken from the role rules in the code. The map on Rep Sales appears as a plain green area in the capture because map tiles were not loaded.

### 16.3 Where to find things in the repository

| UI part | File |
| --- | --- |
| Shell, tabs, drawer, Settings, record screens | `index.html` |
| Standalone screens (11 files) | `screens/*.screen.html` (compiled copies in `screens/compiled/`) |
| Overview markup | `modules/overview-data.js`, `modules/overview.js` |
| Sign-in overlay | `modules/sync.js` |
| Dialogs | `modules/dialog.js` |
| Date picker | `modules/custom-date-picker.js` |
| On-screen keyboard | `modules/virtual-keyboard.js` |
| History cards and record lists | `modules/history-card.js`, `modules/record-list.js` |
| Roles and visibility | `modules/roles.js`, `modules/money-privacy.js` |
| Approvals | `modules/approvals*.js` |
| Calculator pop-ups | `modules/field-expenses.js` |
| Sound and haptics | `modules/feedback.js` |
| Styles, themes, fonts | `app.css`, `fonts/` |
