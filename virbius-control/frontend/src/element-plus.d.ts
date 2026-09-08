// ElMessageBox.confirm ignores zIndex at runtime (uses nextZIndex ~2000).
// Keep overlays above drawer masks via theme.css .el-overlay.is-message-box.
export {};

declare module 'element-plus' {
  interface ElMessageBoxOptions {
    zIndex?: number;
  }
}
