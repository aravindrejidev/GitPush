use serde::{Deserialize, Serialize};

fn yes() -> bool {
    true
}
fn default_max() -> u64 {
    50 * 1024 * 1024
}
fn default_threads() -> usize {
    4
}

/// Request shared by `plan` and `push` (Kotlin sends this as JSON).
#[derive(Deserialize, Clone)]
pub struct EngineRequest {
    pub owner: String,
    pub repo: String,
    pub branch: String,
    #[serde(default)]
    pub base_branch: Option<String>,
    pub token: String,
    pub stage_dir: String,
    #[serde(default)]
    pub target_subdir: String,
    #[serde(default = "yes")]
    pub respect_gitignore: bool,
    #[serde(default)]
    pub extra_ignores: Vec<String>,
    #[serde(default = "default_max")]
    pub max_file_bytes: u64,
    #[serde(default)]
    pub delete_missing: bool,
    #[serde(default)]
    pub message: String,
    #[serde(default)]
    pub author_name: String,
    #[serde(default)]
    pub author_email: String,
    #[serde(default)]
    pub tz_offset: String,
    #[serde(default)]
    pub timestamp: i64,
    #[serde(default = "default_threads")]
    pub threads: usize,
    #[serde(default)]
    pub temp_dir: String,
    #[serde(default)]
    pub api_base: Option<String>,
    #[serde(default)]
    pub git_base: Option<String>,
}

#[derive(Serialize, Clone, Debug)]
pub struct Change {
    pub path: String,
    pub size: u64,
}

#[derive(Serialize, Clone, Debug)]
pub struct Skipped {
    pub path: String,
    pub reason: String,
}

#[derive(Serialize)]
pub struct PlanOut {
    pub added: Vec<Change>,
    pub modified: Vec<Change>,
    pub deleted: Vec<Change>,
    pub added_count: usize,
    pub modified_count: usize,
    pub deleted_count: usize,
    pub unchanged_count: usize,
    pub skipped: Vec<Skipped>,
    pub skipped_count: usize,
    pub upload_bytes: u64,
    pub local_files: usize,
    pub local_bytes: u64,
    pub parent_sha: Option<String>,
    pub branch_exists: bool,
    pub repo_empty: bool,
}

#[derive(Serialize)]
pub struct PushOut {
    /// "pushed" or "up_to_date"
    pub status: String,
    pub commit_sha: Option<String>,
    pub branch: String,
    pub added: usize,
    pub modified: usize,
    pub deleted: usize,
    pub bytes_sent: u64,
    pub remote_messages: Vec<String>,
}

#[derive(Serialize)]
pub struct ExtractOut {
    pub files: u64,
    pub bytes: u64,
    pub stripped_root: Option<String>,
    pub skipped: Vec<Skipped>,
}
