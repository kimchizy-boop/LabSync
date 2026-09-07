LABSYNC COMPLETE WEB PACKAGE
============================

This package contains the current LabSync web application migrated from the desktop system.

FEATURES
- Public opening screen
- Student/User login
- Teacher booking WITHOUT an account
- Public Monitor Borrow
- Admin/Sub-Admin login
- Dashboard
- Borrow and return devices
- Records
- Monthly calendar
- Fastest/slowest return ranking
- Booking management and approval
- Health reports
- Analytics
- App policies
- Device time limits
- User management
- Admin management
- CSV exports
- Responsive mobile layout
- PWA manifest/service worker
- SQLite database

PROJECT STRUCTURE
- pom.xml
- railway.toml
- src/main/java/com/gms/web/GmsWebApplication.java
- src/main/resources/application.properties
- src/main/resources/static/index.html
- src/main/resources/static/app.js
- src/main/resources/static/styles.css
- src/main/resources/static/manifest.json
- src/main/resources/static/sw.js

RAILWAY
- Connect the repository to Railway.
- Keep Root Directory blank.
- Railway detects pom.xml at the repository root.

LOCAL RUN
1. Install JDK 17+ and Maven.
2. Run: mvn spring-boot:run
3. Open: http://localhost:8080
4. The SQLite database is stored at ./data/labsync.db by default.

IMPORTANT
- The included default main admin account is a demo account. Change credentials before real deployment.
- SQLite is local to the server instance. For multiple devices to share the same data, they must connect to the same running server/database.
