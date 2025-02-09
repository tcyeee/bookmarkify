import http from '../http/http';

export * from './typing'

export const sysLinkTest = () => http.get("/test/link?type=3") as Promise<Boolean>;

export const sysLinkTest2 = () => http.get("/test/link?type=3") as Promise<Boolean>;
