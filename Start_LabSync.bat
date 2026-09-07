@echo off
cd /d "%~dp0"
where java >nul 2>nul || (echo JDK 17+ is required.&pause&exit /b 1)
where mvn >nul 2>nul || (echo Maven is required.&pause&exit /b 1)
mvn spring-boot:run
pause
