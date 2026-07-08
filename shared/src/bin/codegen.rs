use std::path::PathBuf;

use clap::{Parser, ValueEnum};
use crux_core::type_generation::facet::{Config, TypeRegistry};
use shared::OpencodeApp;

#[derive(Copy, Clone, PartialEq, Eq, PartialOrd, Ord, ValueEnum)]
enum Language {
    Kotlin,
}

#[derive(Parser)]
#[command(version, about, long_about = None)]
struct Args {
    #[arg(short, long, value_enum)]
    language: Language,
    #[arg(short, long)]
    output_dir: PathBuf,
}

fn main() -> anyhow::Result<()> {
    let args = Args::parse();

    let typegen_app = TypeRegistry::new()
        .register_app::<OpencodeApp>()?
        .build()?;

    let name = match args.language {
        Language::Kotlin => "soy.iko.opencode.core",
    };
    let config = Config::builder(name, &args.output_dir).build();

    match args.language {
        Language::Kotlin => {
            typegen_app.kotlin(&config)?;
        }
    }

    Ok(())
}
