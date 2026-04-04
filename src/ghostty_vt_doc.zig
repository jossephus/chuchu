//! Doc shim that re-exports Ghostty's downstream `ghostty-vt` module.
const ghostty_vt = @import("ghostty-vt");

pub const sys = ghostty_vt.sys;
pub const RenderState = ghostty_vt.RenderState;
pub const Terminal = ghostty_vt.Terminal;
pub const input = ghostty_vt.input;
