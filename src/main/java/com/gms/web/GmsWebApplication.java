package com.gms.web;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.File;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class GmsWebApplication {
    private final String db;
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();

    public GmsWebApplication(Environment e){db=e.getProperty("gms.db","./data/labsync.db");initSchema();}
    public static void main(String[] a){SpringApplication.run(GmsWebApplication.class,a);}

    private Connection c() throws SQLException{
        File f=new File(db).getParentFile(); if(f!=null && !f.exists()) f.mkdirs();
        Connection x=DriverManager.getConnection("jdbc:sqlite:"+db);
        try(Statement s=x.createStatement()){
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
        }
        return x;
    }
    private void initSchema(){
        try(Connection x=c(); Statement st=x.createStatement()){
            st.executeUpdate("CREATE TABLE IF NOT EXISTS admins(id INTEGER PRIMARY KEY AUTOINCREMENT,username VARCHAR(50) UNIQUE,password VARCHAR(255),role VARCHAR(20),full_name VARCHAR(100),laboratory VARCHAR(100),profile_picture BLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS users(username VARCHAR(50) PRIMARY KEY,password VARCHAR(255),full_name VARCHAR(100),email VARCHAR(100),department VARCHAR(100),grade_level VARCHAR(10),section VARCHAR(100),strand VARCHAR(100),profile_picture BLOB,is_blocked INTEGER DEFAULT 0,daily_limit DECIMAL(5,2) DEFAULT 2.0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS borrowed_gadgets(id INTEGER PRIMARY KEY AUTOINCREMENT,gadget_number VARCHAR(10),device VARCHAR(50),laboratory VARCHAR(50),username VARCHAR(50),account_username VARCHAR(50),borrow_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,logout_time TIMESTAMP,status VARCHAR(20) DEFAULT 'Borrowed',batch_id VARCHAR(50),due_time TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS booking_requests(id INTEGER PRIMARY KEY AUTOINCREMENT,full_name VARCHAR(150) NOT NULL,laboratory VARCHAR(50) NOT NULL,device_type VARCHAR(50) NOT NULL,quantity INTEGER NOT NULL,hours DECIMAL(5,2) NOT NULL,booking_date DATE NOT NULL,status VARCHAR(20) DEFAULT 'Pending',approved_by VARCHAR(100),approved_at TIMESTAMP,cancellation_reason TEXT,cancelled_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS device_health_log(id INTEGER PRIMARY KEY AUTOINCREMENT,device_type VARCHAR(50),gadget_number VARCHAR(10),issue_type VARCHAR(100),severity VARCHAR(20),description TEXT,reported_by VARCHAR(50),status VARCHAR(20) DEFAULT 'Pending',reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,resolved_at TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS app_policies(id INTEGER PRIMARY KEY AUTOINCREMENT,device_type VARCHAR(50),gadget_number VARCHAR(10) DEFAULT 'ALL',app_name VARCHAR(100),policy VARCHAR(20))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS device_time_limits(device_type VARCHAR(50) PRIMARY KEY,time_limit DECIMAL(5,2) DEFAULT 2.0,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS app_audit_log(id INTEGER PRIMARY KEY AUTOINCREMENT,username VARCHAR(50),gadget_number VARCHAR(10),device_type VARCHAR(50),app_name VARCHAR(100),action VARCHAR(50),result VARCHAR(20),timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            ensureColumn(st,"borrowed_gadgets","account_username","VARCHAR(50)");
            ensureColumn(st,"users","grade_level","VARCHAR(10)"); ensureColumn(st,"users","section","VARCHAR(100)"); ensureColumn(st,"users","strand","VARCHAR(100)");
            ensureColumn(st,"booking_requests","cancellation_reason","TEXT"); ensureColumn(st,"booking_requests","cancelled_at","TIMESTAMP");
            ensureColumn(st,"app_policies","gadget_number","VARCHAR(10) DEFAULT 'ALL'");
            st.executeUpdate("UPDATE borrowed_gadgets SET account_username=username WHERE account_username IS NULL OR TRIM(account_username)=''");
            try(ResultSet r=st.executeQuery("SELECT COUNT(*) FROM admins WHERE username='Ayheza Kim'")){
                if(r.next()&&r.getInt(1)==0)st.executeUpdate("INSERT INTO admins(username,password,role,full_name) VALUES('Ayheza Kim','kimchi1009','main','Ayheza Kim')");
            }
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_borrow_device_lab_status ON borrowed_gadgets(device,gadget_number,laboratory,status)");
        }catch(Exception e){System.err.println("LabSync schema init: "+e.getMessage());}
    }
    private void ensureColumn(Statement st,String table,String col,String type)throws SQLException{
        boolean exists=false;try(ResultSet r=st.executeQuery("PRAGMA table_info("+table+")")){while(r.next())if(col.equalsIgnoreCase(r.getString("name"))){exists=true;break;}}
        if(!exists)st.executeUpdate("ALTER TABLE "+table+" ADD COLUMN "+col+" "+type);
    }
    private Session auth(String h){return h==null?null:sessions.get(h.replaceFirst("^Bearer ","").trim());}
    private ResponseEntity<Map<String,String>> fail(HttpStatus s,String m){return ResponseEntity.status(s).body(Map.of("message",m));}
    private String v(String s){return s==null?"":s.trim();}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String,String> b){
        String u=v(b.get("username")),p=b.getOrDefault("password","");
        if(u.isEmpty()||p.isEmpty())return fail(HttpStatus.BAD_REQUEST,"Please enter your username and password.");
        try(Connection x=c()){
            Account a=null;
            try(PreparedStatement q=x.prepareStatement("SELECT username,password,role,full_name FROM admins WHERE username=?")){
                q.setString(1,u);try(ResultSet r=q.executeQuery()){
                    if(r.next()&&Objects.equals(r.getString("password"),p))
                        a=new Account(r.getString("username"),v(r.getString("role")),v(r.getString("full_name")));
                }
            }
            if(a==null)try(PreparedStatement q=x.prepareStatement("SELECT username,password,full_name,is_blocked FROM users WHERE username=?")){
                q.setString(1,u);try(ResultSet r=q.executeQuery()){
                    if(r.next()&&Objects.equals(r.getString("password"),p)){
                        if(r.getInt("is_blocked")!=0)return fail(HttpStatus.FORBIDDEN,"This account is blocked.");
                        a=new Account(r.getString("username"),"user",v(r.getString("full_name")));
                    }
                }
            }
            if(a==null)return fail(HttpStatus.UNAUTHORIZED,"Invalid username or password.");
            String t=UUID.randomUUID().toString();sessions.put(t,new Session(a.u,a.role,a.name.isEmpty()?a.u:a.name));
            return ResponseEntity.ok(Map.of("token",t,"username",a.u,"role",a.role,"displayName",a.name.isEmpty()?a.u:a.name));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,"Database error: "+e.getMessage());}
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader("Authorization") String h){
        Session s=auth(h);return s==null?fail(HttpStatus.UNAUTHORIZED,"Please log in."):ResponseEntity.ok(s);
    }

    private String owner(Session s){return "user".equalsIgnoreCase(s.role)?" AND COALESCE(account_username,username)=?":"";}
    private void ownerParam(PreparedStatement p,int i,Session s)throws SQLException{
        if("user".equalsIgnoreCase(s.role))p.setString(i,s.u);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(@RequestHeader("Authorization") String h){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        String d=LocalDate.now().toString();
        try(Connection x=c()){
            int records=count(x,"SELECT COUNT(*) FROM borrowed_gadgets WHERE DATE(borrow_time)=?",d,s);
            int active=count(x,"SELECT COUNT(*) FROM borrowed_gadgets WHERE status IN ('Borrowed','Overdue')",null,s);
            int returned=count(x,"SELECT COUNT(*) FROM borrowed_gadgets WHERE status='Returned' AND DATE(logout_time)=?",d,s);
            List<Map<String,Object>> rows=query(x,
              "SELECT id,gadget_number,device,laboratory,username,borrow_time,logout_time,status,batch_id,due_time FROM borrowed_gadgets WHERE 1=1"+
              owner(s)+" ORDER BY borrow_time DESC,id DESC LIMIT 20",List.of(),s);
            return ResponseEntity.ok(Map.of("records",records,"borrowed",active,"returned",returned,"recent",rows));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/devices")
    public ResponseEntity<?> devices(@RequestHeader("Authorization") String h){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        List<Map<String,Object>> out=new ArrayList<>();
        String[] types={"Laptop","Tablet","Phone","Computer"},labs={"Calroom","Blue lab","Shs lab","Mac lab"};
        try(Connection x=c()){
            for(String d:types)for(String l:labs)for(int n=1;n<=50;n++){
                boolean used=false;
                try(PreparedStatement p=x.prepareStatement("SELECT 1 FROM borrowed_gadgets WHERE gadget_number=? AND device=? AND laboratory=? AND status IN ('Borrowed','Overdue') LIMIT 1")){
                    p.setString(1,""+n);p.setString(2,d);p.setString(3,l);try(ResultSet r=p.executeQuery()){used=r.next();}
                }
                out.add(Map.of("device",d,"number",n,"name",d+" #"+n,"laboratory",l,"status",used?"Borrowed":"Available"));
            }
            return ResponseEntity.ok(out);
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/borrow")
    public ResponseEntity<?> borrow(@RequestHeader("Authorization") String h,@RequestBody Map<String,Object> b){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        String d=v((String)b.get("device")),l=v((String)b.get("laboratory")),u=v((String)b.get("username"));int n;
        try{n=Integer.parseInt(String.valueOf(b.get("number")));}catch(Exception e){return fail(HttpStatus.BAD_REQUEST,"Invalid device number.");}
        if(!Set.of("Laptop","Tablet","Phone","Computer").contains(d)||!Set.of("Calroom","Blue lab","Shs lab","Mac lab").contains(l)||n<1||n>50)
            return fail(HttpStatus.BAD_REQUEST,"Invalid request.");
        if(u.isEmpty())u=s.displayName;
        try(Connection x=c()){
            try(PreparedStatement p=x.prepareStatement("SELECT 1 FROM borrowed_gadgets WHERE gadget_number=? AND device=? AND laboratory=? AND status IN ('Borrowed','Overdue') LIMIT 1")){
                p.setString(1,""+n);p.setString(2,d);p.setString(3,l);try(ResultSet r=p.executeQuery()){if(r.next())return fail(HttpStatus.CONFLICT,d+" #"+n+" is already in use in "+l+".");}
            }
            if("user".equalsIgnoreCase(s.role)&&count(x,"SELECT COUNT(*) FROM borrowed_gadgets WHERE COALESCE(account_username,username)=? AND status IN ('Borrowed','Overdue')",s.u,null)>=2)
                return fail(HttpStatus.CONFLICT,"Your current borrowing limit has been reached.");
            try(PreparedStatement p=x.prepareStatement("INSERT INTO borrowed_gadgets(gadget_number,device,laboratory,username,account_username,status,batch_id,due_time) VALUES(?,?,?,?,?,'Borrowed',?,datetime('now','+2 hours'))")){
                p.setString(1,""+n);p.setString(2,d);p.setString(3,l);p.setString(4,u);p.setString(5,"user".equalsIgnoreCase(s.role)?s.u:u);p.setString(6,"WEB-"+System.currentTimeMillis());p.executeUpdate();
            }
            return ResponseEntity.ok(Map.of("message","Device borrowed successfully.","device",d+" #"+n,"laboratory",l));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/return")
    public ResponseEntity<?> ret(@RequestHeader("Authorization") String h,@RequestBody Map<String,Object> b){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        Object o=b.get("ids");if(!(o instanceof List<?> ids)||ids.isEmpty())return fail(HttpStatus.BAD_REQUEST,"Select at least one active device.");
        try(Connection x=c()){int n=0;
            String q="UPDATE borrowed_gadgets SET logout_time=CURRENT_TIMESTAMP,status='Returned' WHERE id=? AND status IN ('Borrowed','Overdue')"+owner(s);
            try(PreparedStatement p=x.prepareStatement(q)){for(Object id:ids){p.setInt(1,Integer.parseInt(""+id));if("user".equalsIgnoreCase(s.role))p.setString(2,s.u);n+=p.executeUpdate();}}
            return ResponseEntity.ok(Map.of("message",n+" device(s) returned.","changed",n));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/records")
    public ResponseEntity<?> records(@RequestHeader("Authorization") String h,@RequestParam(defaultValue="all") String date){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        try(Connection x=c()){
            String q="SELECT id,gadget_number,device,laboratory,username,borrow_time,logout_time,status,batch_id,due_time FROM borrowed_gadgets WHERE 1=1"+owner(s);
            List<Object> p=new ArrayList<>();if(!"all".equalsIgnoreCase(date)){q+=" AND DATE(borrow_time)=?";p.add("today".equalsIgnoreCase(date)?LocalDate.now().toString():LocalDate.parse(date).toString());}
            q+=" ORDER BY borrow_time DESC,id DESC";return ResponseEntity.ok(query(x,q,p,s));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/calendar")
    public ResponseEntity<?> calendar(@RequestHeader("Authorization") String h,@RequestParam int year,@RequestParam int month){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        try(Connection x=c()){String q="SELECT DATE(borrow_time) day,COUNT(*) count FROM borrowed_gadgets WHERE strftime('%Y',borrow_time)=? AND strftime('%m',borrow_time)=?"+owner(s)+" GROUP BY DATE(borrow_time) ORDER BY day";
            return ResponseEntity.ok(query(x,q,List.of(""+year,String.format("%02d",month)),s));}
        catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/ranking")
    public ResponseEntity<?> ranking(@RequestHeader("Authorization") String h,@RequestParam(defaultValue="today") String date){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        String d="today".equalsIgnoreCase(date)?LocalDate.now().toString():LocalDate.parse(date).toString();
        try(Connection x=c()){
            String base="SELECT username,device,laboratory,ROUND((julianday(logout_time)-julianday(borrow_time))*24,2) time_used FROM borrowed_gadgets WHERE status='Returned' AND logout_time IS NOT NULL AND DATE(logout_time)=?"+owner(s);
            List<Object> p=List.of(d);
            return ResponseEntity.ok(Map.of("date",d,"fastest",query(x,base+" ORDER BY time_used ASC LIMIT 10",p,s),"slowest",query(x,base+" ORDER BY time_used DESC LIMIT 10",p,s)));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    private boolean admin(Session s){return s!=null&&("main".equalsIgnoreCase(s.role)||"sub".equalsIgnoreCase(s.role));}
    private String ov(Object o){return o==null?"":String.valueOf(o).trim();}

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value="Authorization",required=false) String h){
        if(h!=null)sessions.remove(h.replaceFirst("^Bearer ","").trim());
        return ResponseEntity.ok(Map.of("message","Signed out."));
    }

    @GetMapping("/analytics")
    public ResponseEntity<?> analytics(@RequestHeader("Authorization")String h){
        Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");
        try(Connection x=c()){
            Map<String,Object>m=new LinkedHashMap<>();
            m.put("deviceUsage",query(x,"SELECT device,COUNT(*) total,SUM(CASE WHEN status IN ('Borrowed','Overdue') THEN 1 ELSE 0 END) active,SUM(CASE WHEN status='Returned' THEN 1 ELSE 0 END) returned FROM borrowed_gadgets GROUP BY device ORDER BY total DESC",List.of(),s));
            m.put("labUsage",query(x,"SELECT laboratory,COUNT(*) total FROM borrowed_gadgets GROUP BY laboratory ORDER BY total DESC",List.of(),s));
            m.put("daily",query(x,"SELECT DATE(borrow_time) day,COUNT(*) borrowed FROM borrowed_gadgets WHERE DATE(borrow_time)>=DATE('now','-6 day') GROUP BY DATE(borrow_time) ORDER BY day",List.of(),s));
            m.put("users",query(x,"SELECT u.username,COALESCE(u.full_name,u.username) name,COUNT(b.id) borrow_count,SUM(CASE WHEN b.status IN ('Borrowed','Overdue') THEN 1 ELSE 0 END) active_count,SUM(CASE WHEN b.status='Returned' THEN 1 ELSE 0 END) returned_count FROM users u LEFT JOIN borrowed_gadgets b ON u.username=b.username GROUP BY u.username ORDER BY borrow_count DESC",List.of(),s));
            return ResponseEntity.ok(m);
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/public/borrowings")
    public ResponseEntity<?> publicBorrowings(){
        try(Connection x=c()){
            return ResponseEntity.ok(query(x,"SELECT gadget_number,device,laboratory,borrow_time,status FROM borrowed_gadgets WHERE status IN ('Borrowed','Overdue') ORDER BY borrow_time DESC",List.of(),null));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/bookings")
    public ResponseEntity<?> bookings(@RequestHeader("Authorization")String h,@RequestParam(defaultValue="all")String status,@RequestParam(defaultValue="all")String date){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        try(Connection x=c()){
            String q="SELECT id,full_name,laboratory,device_type,quantity,hours,booking_date,status,COALESCE(approved_by,'-') approved_by,COALESCE(approved_at,'-') approved_at,COALESCE(cancellation_reason,'-') cancellation_reason,COALESCE(cancelled_at,'-') cancelled_at,created_at FROM booking_requests WHERE 1=1";
            List<Object>p=new ArrayList<>();
            if("user".equalsIgnoreCase(s.role)){q+=" AND full_name=?";p.add(s.displayName);}
            if(!"all".equalsIgnoreCase(status)){q+=" AND status=?";p.add(status);}
            if(!"all".equalsIgnoreCase(date)){q+=" AND booking_date=?";p.add("today".equalsIgnoreCase(date)?LocalDate.now().toString():date);}
            q+=" ORDER BY booking_date DESC,created_at DESC";
            return ResponseEntity.ok(query(x,q,p,null));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> createBooking(@RequestBody Map<String,Object>b){
        // Booking is intentionally account-free so teachers can submit requests without an account.
        String name=ov(b.get("fullName"));
        String lab=ov(b.get("laboratory")),dev=ov(b.get("deviceType")),date=ov(b.get("bookingDate"));int qty;double hours;
        try{qty=Integer.parseInt(ov(b.get("quantity")));hours=Double.parseDouble(ov(b.get("hours")));}catch(Exception e){return fail(HttpStatus.BAD_REQUEST,"Quantity and hours must be valid.");}
        if(!Set.of("Calroom","Blue lab","Shs lab","Mac lab").contains(lab)||!Set.of("Laptop","Tablet","Phone","Computer").contains(dev)||qty<1||qty>50||hours<=0||date.isEmpty())return fail(HttpStatus.BAD_REQUEST,"Invalid booking request.");
        try(Connection x=c();PreparedStatement p=x.prepareStatement("INSERT INTO booking_requests(full_name,laboratory,device_type,quantity,hours,booking_date,status) VALUES(?,?,?,?,?,?, 'Pending')")){
            p.setString(1,name);p.setString(2,lab);p.setString(3,dev);p.setInt(4,qty);p.setDouble(5,hours);p.setString(6,date);p.executeUpdate();return ResponseEntity.ok(Map.of("message","Booking request submitted for approval."));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/bookings/{id}/status")
    public ResponseEntity<?> bookingStatus(@RequestHeader("Authorization")String h,@PathVariable int id,@RequestBody Map<String,Object>b){
        Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");String st=ov(b.get("status"));
        if(!Set.of("Approved","Declined","Cancelled").contains(st))return fail(HttpStatus.BAD_REQUEST,"Invalid booking status.");
        try(Connection x=c();PreparedStatement p=x.prepareStatement("UPDATE booking_requests SET status=?,approved_by=?,approved_at=CURRENT_TIMESTAMP WHERE id=?")){
            p.setString(1,st);p.setString(2,s.displayName);p.setInt(3,id);int n=p.executeUpdate();return n==0?fail(HttpStatus.NOT_FOUND,"Booking not found."):ResponseEntity.ok(Map.of("message","Booking #"+id+" marked "+st+"."));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/bookings/{id}/cancel")
    public ResponseEntity<?> cancelBooking(@RequestHeader("Authorization")String h,@PathVariable int id,@RequestBody Map<String,Object>b){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");String reason=ov(b.get("reason"));if(reason.isEmpty())reason="Cancelled by requester";
        try(Connection x=c()){
            String q="UPDATE booking_requests SET status='Cancelled',cancellation_reason=?,cancelled_at=CURRENT_TIMESTAMP WHERE id=? AND status='Pending'"+("user".equalsIgnoreCase(s.role)?" AND full_name=?":"");
            try(PreparedStatement p=x.prepareStatement(q)){p.setString(1,reason);p.setInt(2,id);if("user".equalsIgnoreCase(s.role))p.setString(3,s.displayName);int n=p.executeUpdate();return n==0?fail(HttpStatus.CONFLICT,"Booking cannot be cancelled."):ResponseEntity.ok(Map.of("message","Booking cancelled."));}
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/health")
    public ResponseEntity<?> health(@RequestHeader("Authorization")String h){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        try(Connection x=c()){String q="SELECT id,device_type,gadget_number,issue_type,severity,description,reported_by,status,reported_at,resolved_at FROM device_health_log WHERE 1=1"+(admin(s)?"":" AND reported_by=?")+" ORDER BY reported_at DESC";return ResponseEntity.ok(query(x,q,List.of(),admin(s)?null:s));}
        catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/health")
    public ResponseEntity<?> reportHealth(@RequestHeader("Authorization")String h,@RequestBody Map<String,Object>b){
        Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");
        try(Connection x=c();PreparedStatement p=x.prepareStatement("INSERT INTO device_health_log(device_type,gadget_number,issue_type,severity,description,reported_by,status) VALUES(?,?,?,?,?,?, 'Pending')")){
            p.setString(1,ov(b.get("deviceType")));p.setString(2,ov(b.get("gadgetNumber")));p.setString(3,ov(b.get("issueType")));p.setString(4,ov(b.get("severity")));p.setString(5,ov(b.get("description")));p.setString(6,s.displayName);p.executeUpdate();return ResponseEntity.ok(Map.of("message","Health report submitted."));
        }catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/health/{id}/resolve")
    public ResponseEntity<?> resolveHealth(@RequestHeader("Authorization")String h,@PathVariable int id){
        Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");
        try(Connection x=c();PreparedStatement p=x.prepareStatement("UPDATE device_health_log SET status='Resolved',resolved_at=CURRENT_TIMESTAMP WHERE id=?")){p.setInt(1,id);return ResponseEntity.ok(Map.of("message",p.executeUpdate()>0?"Health report resolved.":"Report not found."));}
        catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/users")
    public ResponseEntity<?> users(@RequestHeader("Authorization")String h){
        Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin access required.");
        try(Connection x=c()){return ResponseEntity.ok(query(x,"SELECT username,COALESCE(full_name,username) full_name,email,grade_level,section,strand,daily_limit,is_blocked,created_at FROM users ORDER BY full_name,username",List.of(),s));}
        catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestHeader("Authorization")String h,@RequestBody Map<String,Object>b){
        Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin access required.");String name=ov(b.get("fullName"));if(name.isEmpty())return fail(HttpStatus.BAD_REQUEST,"Full name is required.");String pwd=ov(b.get("password"));if(pwd.isEmpty())pwd="SVNHS@1";
        try(Connection x=c();PreparedStatement p=x.prepareStatement("INSERT INTO users(username,password,full_name,email,grade_level,section,strand,department,daily_limit) VALUES(?,?,?,?,?,?,?,?,?)")){
            p.setString(1,name);p.setString(2,pwd);p.setString(3,name);p.setString(4,ov(b.get("email")));p.setString(5,ov(b.get("gradeLevel")));p.setString(6,ov(b.get("section")));p.setString(7,ov(b.get("strand")));p.setString(8,ov(b.get("gradeLevel")));p.setDouble(9,2.0);p.executeUpdate();return ResponseEntity.ok(Map.of("message","Student created.","username",name));
        }catch(Exception e){return fail(HttpStatus.CONFLICT,"Could not create student: "+e.getMessage());}
    }

    @PostMapping("/users/{username}/block")
    public ResponseEntity<?> blockUser(@RequestHeader("Authorization")String h,@PathVariable String username,@RequestBody Map<String,Object>b){
        Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin access required.");int blocked=Boolean.parseBoolean(ov(b.get("blocked")))?1:0;
        try(Connection x=c();PreparedStatement p=x.prepareStatement("UPDATE users SET is_blocked=? WHERE username=?")){p.setInt(1,blocked);p.setString(2,username);return ResponseEntity.ok(Map.of("message",blocked==1?"User blocked.":"User unblocked."));}
        catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @GetMapping("/admins")
    public ResponseEntity<?> admins(@RequestHeader("Authorization")String h){
        Session s=auth(h);if(s==null||!"main".equalsIgnoreCase(s.role))return fail(HttpStatus.FORBIDDEN,"Main Admin access required.");
        try(Connection x=c()){return ResponseEntity.ok(query(x,"SELECT username,COALESCE(full_name,username) full_name,role,laboratory,created_at FROM admins ORDER BY role,username",List.of(),s));}
        catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}
    }

    @PostMapping("/admins")
    public ResponseEntity<?> createAdmin(@RequestHeader("Authorization")String h,@RequestBody Map<String,Object>b){
        Session s=auth(h);if(s==null||!"main".equalsIgnoreCase(s.role))return fail(HttpStatus.FORBIDDEN,"Main Admin access required.");String u=ov(b.get("username")),pwd=ov(b.get("password"));if(u.isEmpty()||pwd.isEmpty())return fail(HttpStatus.BAD_REQUEST,"Username and password are required.");
        try(Connection x=c();PreparedStatement p=x.prepareStatement("INSERT INTO admins(username,password,role,full_name,laboratory) VALUES(?,?, 'sub',?,?)")){
            p.setString(1,u);p.setString(2,pwd);p.setString(3,ov(b.get("fullName")).isEmpty()?u:ov(b.get("fullName")));p.setString(4,ov(b.get("laboratory")));p.executeUpdate();return ResponseEntity.ok(Map.of("message","Sub-Admin created."));
        }catch(Exception e){return fail(HttpStatus.CONFLICT,"Could not create admin: "+e.getMessage());}
    }

    @GetMapping("/app-policies")
    public ResponseEntity<?> policies(@RequestHeader("Authorization")String h){Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");try(Connection x=c()){return ResponseEntity.ok(query(x,"SELECT id,device_type,gadget_number,app_name,policy FROM app_policies ORDER BY device_type,gadget_number,app_name",List.of(),s));}catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}}
    @PostMapping("/app-policies")
    public ResponseEntity<?> savePolicy(@RequestHeader("Authorization")String h,@RequestBody Map<String,Object>b){Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");try(Connection x=c();PreparedStatement p=x.prepareStatement("INSERT INTO app_policies(device_type,gadget_number,app_name,policy) VALUES(?,?,?,?)")){p.setString(1,ov(b.get("deviceType")));p.setString(2,ov(b.get("gadgetNumber")).isEmpty()?"ALL":ov(b.get("gadgetNumber")));p.setString(3,ov(b.get("appName")));p.setString(4,ov(b.get("policy")));p.executeUpdate();return ResponseEntity.ok(Map.of("message","App policy saved."));}catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}}
    @DeleteMapping("/app-policies/{id}")
    public ResponseEntity<?> deletePolicy(@RequestHeader("Authorization")String h,@PathVariable int id){Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");try(Connection x=c();PreparedStatement p=x.prepareStatement("DELETE FROM app_policies WHERE id=?")){p.setInt(1,id);p.executeUpdate();return ResponseEntity.ok(Map.of("message","Policy removed."));}catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}}

    @GetMapping("/time-limits")
    public ResponseEntity<?> limits(@RequestHeader("Authorization")String h){Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");try(Connection x=c()){return ResponseEntity.ok(query(x,"SELECT device_type,time_limit,updated_at FROM device_time_limits ORDER BY device_type",List.of(),s));}catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}}
    @PostMapping("/time-limits")
    public ResponseEntity<?> saveLimit(@RequestHeader("Authorization")String h,@RequestBody Map<String,Object>b){Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");try(Connection x=c();PreparedStatement p=x.prepareStatement("INSERT INTO device_time_limits(device_type,time_limit,updated_at) VALUES(?,?,CURRENT_TIMESTAMP) ON CONFLICT(device_type) DO UPDATE SET time_limit=excluded.time_limit,updated_at=CURRENT_TIMESTAMP")){p.setString(1,ov(b.get("deviceType")));p.setDouble(2,Double.parseDouble(ov(b.get("hours"))));p.executeUpdate();return ResponseEntity.ok(Map.of("message","Time limit saved."));}catch(Exception e){return fail(HttpStatus.BAD_REQUEST,"Invalid time limit.");}}

    @PostMapping("/clear-active")
    public ResponseEntity<?> clearActive(@RequestHeader("Authorization")String h){Session s=auth(h);if(!admin(s))return fail(HttpStatus.FORBIDDEN,"Admin/Sub-Admin access required.");try(Connection x=c();PreparedStatement p=x.prepareStatement("DELETE FROM borrowed_gadgets WHERE status IN ('Borrowed','Overdue')")){int n=p.executeUpdate();return ResponseEntity.ok(Map.of("message",n+" active record(s) cleared.","changed",n));}catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}}

    @GetMapping("/profile")
    public ResponseEntity<?> profile(@RequestHeader("Authorization")String h){Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");String t=admin(s)?"admins":"users";try(Connection x=c();PreparedStatement p=x.prepareStatement("SELECT * FROM "+t+" WHERE username=?")){p.setString(1,s.u);try(ResultSet r=p.executeQuery()){if(!r.next())return fail(HttpStatus.NOT_FOUND,"Profile not found.");Map<String,Object>m=new LinkedHashMap<>();ResultSetMetaData md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++){String n=md.getColumnLabel(i);if(!"password".equalsIgnoreCase(n)&&!"profile_picture".equalsIgnoreCase(n))m.put(n,r.getObject(i));}return ResponseEntity.ok(m);}}catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}}

    @PostMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestHeader("Authorization")String h,@RequestBody Map<String,Object>b){Session s=auth(h);if(s==null)return fail(HttpStatus.UNAUTHORIZED,"Please log in.");try(Connection x=c()){if(admin(s)){try(PreparedStatement p=x.prepareStatement("UPDATE admins SET full_name=?,laboratory=? WHERE username=?")){p.setString(1,ov(b.get("fullName")));p.setString(2,ov(b.get("laboratory")));p.setString(3,s.u);p.executeUpdate();}}else{try(PreparedStatement p=x.prepareStatement("UPDATE users SET full_name=?,email=?,grade_level=?,section=?,strand=?,department=? WHERE username=?")){String g=ov(b.get("gradeLevel"));p.setString(1,ov(b.get("fullName")));p.setString(2,ov(b.get("email")));p.setString(3,g);p.setString(4,ov(b.get("section")));p.setString(5,ov(b.get("strand")));p.setString(6,g);p.setString(7,s.u);p.executeUpdate();}}return ResponseEntity.ok(Map.of("message","Profile updated."));}catch(Exception e){return fail(HttpStatus.INTERNAL_SERVER_ERROR,e.getMessage());}}

    private int count(Connection x,String q,String val,Session s)throws SQLException{
        q+=owner(s);try(PreparedStatement p=x.prepareStatement(q)){int i=1;if(val!=null)p.setString(i++,val);ownerParam(p,i,s);try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}}
    }
    private List<Map<String,Object>> query(Connection x,String q,List<Object> vals,Session s)throws SQLException{
        List<Map<String,Object>> a=new ArrayList<>();try(PreparedStatement p=x.prepareStatement(q)){int i=1;for(Object z:vals)p.setObject(i++,z);ownerParam(p,i,s);try(ResultSet r=p.executeQuery()){ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int k=1;k<=m.getColumnCount();k++)row.put(m.getColumnLabel(k),r.getObject(k));a.add(row);}}}return a;
    }
    record Session(String u,String role,String displayName){}
    record Account(String u,String role,String name){}
}
