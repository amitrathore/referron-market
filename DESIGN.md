# Referron Market design

The brand source is https://www.referron.com/, inspected 2 November 2026.
Referron Market extends that identity into a marketplace. Agents of Mind supplies
application behavior, not the visual identity.

## Official assets

Local files in `resources/ui/assets/brand/` are downloaded originals:

| File | Public source |
| --- | --- |
| referron-logo.png | https://www.referron.com/_next/static/media/logo.0oe4s6_okyx-r.png |
| favicon.png | https://www.referron.com/icon.png |
| apple-touch-icon.png | https://www.referron.com/apple-icon.png |
| inter-latin.woff2 | https://www.referron.com/_next/static/media/83afe278b6a6bb3c-s.p.2bn3s6zvc0dyp.woff2 |

The official logo is 1153 × 340. Preserve its aspect ratio. The adjacent “Market”
label identifies this application without altering the wordmark. Use a white
backing for the logo in the optional dark theme. Serve assets locally.

## Tokens

Extracted from the website HTML and its Inter font stylesheet:

| Role | Value |
| --- | --- |
| Brand blue | #0294DB |
| Action blue | #0172B5 |
| Deep blue | #006593 |
| Text | #1D1D1F |
| Secondary text | #6E6E73 |
| Divider | #D2D2D7 |
| Page background | #F5F5F7 |
| Surface | #FFFFFF |

Inter is used throughout, with locally hosted variable weights. The darker
website blue is used for filled actions and small text to improve contrast.
Light blue #EDF8FE is a marketplace supporting tint. Dark-theme tokens are
application adaptations, not claimed as an official Referron dark palette.

## Layout and interaction

Use a clear shopping hierarchy, generous whitespace, rounded controls and
restrained blue accents. The home page's connection diagram illustrates
Discover → Refer → Grow; it represents a process, not live network activity.
Products and seller profiles use actual protocol state. Empty markets invite
seller participation without fabricated inventory, ratings, or earnings.

Maintain keyboard focus, 44px interaction targets, legible contrast, and
reduced-motion behavior. Test at 320, 390, 768, and 1440px. Keep role and protocol
terminology out of ordinary shopping decisions where plain language suffices.

## Social identity

`social-card-v2.png` is a dedicated 1200 × 630 share card: deep blue background,
white/sky-blue Inter typography, and an illuminated connection network built
around the unchanged official Referron icon. Text: “Good connections. Great
possibilities.” Description: “Discover products and services worth sharing.
Make trusted recommendations, open new doors, and earn from the value you help
create.” The dark card is an application composition using the established blue.

Rebuild with `npm run build:brand`. Its editable source is
`scripts/brand/social-card.html`; Chromium renders the final PNG with local fonts.
No AI-generated or redrawn logo is used. The favicon family displays the icon
portion of the original high-resolution wordmark sprite and preserves its pixels.
The SVG is self-contained; PNG fallbacks exist at 16, 32, 48, 180, 192 and 512px,
plus a multi-resolution ICO. Versioned filenames refresh browser and unfurl caches.
