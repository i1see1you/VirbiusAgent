use std::fs;
use std::io::Write;
use std::path::PathBuf;
use std::time::Duration;

const FALCO_RULES_DIR: &str = "/etc/falco/falco_rules.d";
const REDIS_STREAMS: &[&str] = &[
    "virbius:falco:rule-update:canary",
    "virbius:falco:rule-update:full",
];
const CONSUMER_GROUP: &str = "falco-config-subscriber";
const CONSUMER_ID: &str = "falco-node";

pub fn run() {
    let redis_url =
        std::env::var("VIRBIUS_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());

    let client = match redis::Client::open(redis_url.as_str()) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("config_subscriber: failed to connect to Redis: {}", e);
            return;
        }
    };

    let mut con = match client.get_connection() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("config_subscriber: failed to get connection: {}", e);
            return;
        }
    };

    // Create consumer groups if needed
    for stream in REDIS_STREAMS {
        let _: Result<(), _> = redis::cmd("XGROUP")
            .arg("CREATE")
            .arg(stream)
            .arg(CONSUMER_GROUP)
            .arg("$")
            .arg("MKSTREAM")
            .query(&mut con);
    }

    loop {
        for stream in REDIS_STREAMS {
            // Clippy: the nested generic type is dictated by the redis crate's
            // XREADGROUP return shape. Allow for readability.
            #[allow(clippy::type_complexity)]
            let result: Result<Vec<(String, Vec<(String, Vec<(String, String)>)>)>, _> =
                redis::cmd("XREADGROUP")
                    .arg("GROUP")
                    .arg(CONSUMER_GROUP)
                    .arg(CONSUMER_ID)
                    .arg("COUNT")
                    .arg(1)
                    .arg("BLOCK")
                    .arg(5000)
                    .arg("STREAMS")
                    .arg(stream)
                    .arg(">")
                    .query(&mut con);

            match result {
                Ok(streams) => {
                    for (_stream_name, messages) in &streams {
                        for (msg_id, fields) in messages {
                            if let Err(e) = handle_message(&mut con, fields) {
                                eprintln!("config_subscriber: error processing {}: {}", msg_id, e);
                            }
                            // Acknowledge the message
                            let _: Result<(), _> = redis::cmd("XACK")
                                .arg(stream)
                                .arg(CONSUMER_GROUP)
                                .arg(msg_id)
                                .query(&mut con);
                        }
                    }
                }
                Err(e) => {
                    eprintln!("config_subscriber: XREADGROUP error: {}", e);
                }
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn handle_message(
    con: &mut redis::Connection,
    fields: &[(String, String)],
) -> Result<(), Box<dyn std::error::Error>> {
    let mut tenant_id = String::new();
    let mut revision: i64 = 0;
    let mut target = String::new();

    for (k, v) in fields {
        match k.as_str() {
            "tenant_id" => tenant_id = v.clone(),
            "revision" => revision = v.parse().unwrap_or(0),
            "target" => target = v.clone(),
            _ => {}
        }
    }

    if tenant_id.is_empty() || revision == 0 {
        return Err("missing tenant_id or revision".into());
    }

    // Fetch the rules YAML from Redis
    let artifact_key = format!("virbius:falco:artifact:{}:{}", tenant_id, revision);
    let rules_yaml: Option<String> = redis::cmd("GET").arg(&artifact_key).query(con)?;

    let rules_yaml = match rules_yaml {
        Some(y) => y,
        None => return Err(format!("artifact not found: {}", artifact_key).into()),
    };

    // Write rules to file
    let rules_dir = PathBuf::from(FALCO_RULES_DIR);
    fs::create_dir_all(&rules_dir)?;

    let file_path = rules_dir.join(format!("{}-{}.yaml", tenant_id, target));
    let mut file = fs::File::create(&file_path)?;
    file.write_all(rules_yaml.as_bytes())?;
    file.flush()?;

    // Send SIGHUP to Falco process
    send_sighup_to_falco();

    Ok(())
}

fn send_sighup_to_falco() {
    #[cfg(target_os = "linux")]
    {
        let output = std::process::Command::new("pgrep").arg("falco").output();
        if let Ok(output) = output {
            let pids = String::from_utf8_lossy(&output.stdout);
            for line in pids.lines() {
                if let Ok(pid) = line.trim().parse::<i32>() {
                    unsafe {
                        libc::kill(pid, libc::SIGHUP);
                    }
                }
            }
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = (); // SIGHUP only supported on Linux
    }
}
