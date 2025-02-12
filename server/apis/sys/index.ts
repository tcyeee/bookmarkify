import http from '../http/http';

export * from './typing'

export const sysLinkTest = () => http.get("/test/link") as Promise<string>;
