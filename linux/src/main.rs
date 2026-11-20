//! SuperAwesomeMagicalOrchestrator's agent for Linux: a user service on the session bus and
//! the command that talks to it. `docs/PROTOCOL.md` is the contract.

mod api;
mod cli;
mod daemon;
mod links;
mod monitor;
mod notes;
mod seal;
mod signin;
mod store;
mod system;

use std::process::ExitCode;

use clap::Parser;

#[tokio::main]
async fn main() -> ExitCode {
    let cli = cli::Cli::parse();
    // One TLS provider for everything (the server's line and its API), and the system's
    // certificates: a private certificate authority installed on the computer is trusted.
    let _ = rustls::crypto::ring::default_provider().install_default();
    match cli::run(cli).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error:#}");
            ExitCode::FAILURE
        }
    }
}
