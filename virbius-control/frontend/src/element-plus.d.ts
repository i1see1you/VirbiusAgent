// element-plus MessageBox merges unknown options onto the component state at
// runtime, so passing zIndex works, but upstream ElMessageBoxOptions does not
// declare it — views pass explicit z-index values to keep dialogs on top.
export {};

declare module 'element-plus' {
  interface ElMessageBoxOptions {
    zIndex?: number;
  }
}
