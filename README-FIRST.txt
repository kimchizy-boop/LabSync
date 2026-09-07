GMS WEB VERSION — SETUP

1. Keep your existing data/labsync.db.
2. Open this folder as a Maven project in NetBeans.
3. Run com.gms.web.GmsWebApplication.
4. Open http://localhost:8080.

The web app is configured for ./data/labsync.db, matching the desktop project.
Do not make a second database if you want the web and desktop versions to share records.

For phone/tablet testing on the same Wi-Fi, open http://YOUR-PC-LAN-IP:8080.
For a public Internet site, the backend and this SQLite file must live on an online server.
A static-only host cannot run the Java backend.

Included now:
- Existing SQLite login
- Responsive purple GMS interface
- Dashboard
- Laptop/Tablet/Phone/Computer device catalog, 1–50
- Four laboratories
- Lab-specific availability
- Borrow + editable borrower name
- Return selected devices
- Records
- Monthly calendar and no-borrow-date message
- Fastest/slowest ranking by selected date
- Browser-local settings
- PWA install support

The larger desktop-only admin tools still need their web pages migrated.
The database schema is intentionally not replaced with a new database.
