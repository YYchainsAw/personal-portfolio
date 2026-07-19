export interface ReadyRouter { isReady(): Promise<unknown> }
export interface MountableApp { mount(root: string): unknown }

export async function mountWhenRouterReady(
  app: MountableApp,
  router: ReadyRouter,
  root = '#app',
): Promise<boolean> {
  try {
    await router.isReady()
    app.mount(root)
    return true
  } catch {
    // Keep the server-rendered shell intact when route bootstrap fails.
    return false
  }
}
