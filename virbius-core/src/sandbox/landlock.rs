/// Landlock + drop caps subprocess sandbox (P2, Linux only).

pub struct LandlockSandbox;

impl LandlockSandbox {
    pub fn new() -> Self { Self }
    pub fn execute(&self, _program: &str, _args: &[String]) -> Result<String, String> {
        Err("P2: not yet implemented".into())
    }
}
