//! Crux type-generation for the shared domain (built with `--features typegen`).
//!
//! Demonstrates the Crux type-sharing pipeline: the core's view types are the
//! single source of truth, and this binary emits matching JVM type definitions
//! for the shell to consume across an FFI boundary. In this NDK-less build the
//! Android shell hand-mirrors these types (see `app/.../core/ViewModel.kt`);
//! wiring a real `.so` would have it consume the generated output instead.
//!
//! Run: `cd shared && cargo run --features typegen --bin codegen`
//! Output: `generated/` (JVM/Java sources + the bincode serde runtime).

use std::path::PathBuf;

use anyhow::Result;
use crux_core::typegen::TypeGen;
use shared::{MessageStatus, MessageView, Screen, SessionView, ToolView, ViewModel};

fn main() -> Result<()> {
    let mut gen = TypeGen::new();

    // Register leaf types before the aggregates that reference them.
    gen.register_type::<Screen>()?;
    gen.register_type::<MessageStatus>()?;
    gen.register_type::<ToolView>()?;
    gen.register_type::<SessionView>()?;
    gen.register_type::<MessageView>()?;
    gen.register_type::<ViewModel>()?;

    let out = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../generated");
    std::fs::create_dir_all(&out)?;
    gen.java("soy.iko.opencode.shared", &out)?;

    println!("Generated shared JVM types into {}", out.display());
    Ok(())
}
