declare module '*.vue' {
  import { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module 'vuuri' {
  export const GridEvent: Record<string, string>
  const Vuuri: any
  export default Vuuri
}
