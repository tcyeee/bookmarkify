import { defineStore } from 'pinia'
// import type { LoginParams, UserStore } from '~/apis/'
// import { authLogin } from '~/apis'
// import { ElNotification } from 'element-plus'
// import FingerprintJS from '@fingerprintjs/fingerprintjs';

export const useBaseStore = defineStore('user', {
  state: () => ({
    count: 0
  }),
  actions: {

    setFingerprint(visitorId: string) {
      console.log(visitorId);

    },
    increment() {
      this.count++
    },
    decrement() {
      this.count--
    }
  }
})

// function storeSetup() {
//   const userInfo = ref<UserStore>()
//   const profile = computed(() => userInfo.value)
//   const hasLogin = computed(() => !!userInfo.value?.mail)
//   const loginEvn: Array<Function> = [];

//   /* 使用浏览器指纹对用户信息初始化 */
//   const fingerPrintInit = () => {
//     FingerprintJS.load().then(fingerprint => {
//       fingerprint.get().then(result => {
//         console.log("--------------------------------");
//         console.log(result);  // 获取浏览器指纹
//         console.log("--------------------------------");
//       });
//     });


//     // 选择哪些信息作为浏览器指纹生成的依据
//     const options: any = {
//       excludes: {
//         userAgent: false,                // 用户代理
//         colorDepth: false,               // 目标设备或缓冲器上的调色板的比特深度
//         pixelRatio: false,               // 设备像素比
//         screenResolution: false,         // 当前屏幕分辨率
//         availableScreenResolution: false,// 屏幕宽高（空白空间）
//         timezoneOffset: false,           // 本地时间与 GMT 时间之间的时间差，以分钟为单位
//         sessionStorage: false,           // 是否会话存储
//         indexedDb: false,                // 是否具有索引DB
//         addBehavior: false,              // IE是否指定AddBehavior
//         openDatabase: false,             // 是否有打开的DB
//         doNotTrack: false,               // do-not-track设置
//         plugins: false,                  // 浏览器的插件信息
//         canvas: false,                   // 使用 Canvas 绘图
//         adBlock: false,                  // 是否安装AdBlock
//         hasLiedResolution: false,        // 用户是否篡改了屏幕分辨率
//         hasLiedBrowser: false,           // 用户是否篡改了浏览器
//         fonts: false,                    // 使用JS/CSS检测到的字体列表
//         fontsFlash: false,               // 已安装的Flash字体列表
//         enumerateDevices: false,         // 可用的多媒体输入和输出设备的信息。
//       },
//     };
//     // 浏览器指纹
//     // Fingerprint2.get(options, (components) => { // 参数只有回调函数或者options为{}时，默认浏览器指纹依据所有配置信息进行生成
//     //   const values = components.map(component => component.value); // 配置的值的数组
//     //   const murmur = Fingerprint2.x64hash128(values.join(''), 31); // 生成浏览器指纹

//     //   console.info(`开始生成用户指纹:${murmur}`);
//     //   authInit(murmur).then((res: any) => {
//     //     userInfo.value = res
//     //     loginEvn.forEach(evn => evn.call(evn))
//     //   })
//     // });
//   }

//   const setUserInfo = (info: UserStore) => {
//     userInfo.value = info
//   }

//   const login = async (params: LoginParams) => {
//     await authLogin(params).then((res: any) => {
//       setUserInfo(res)
//       ElNotification({
//         title: 'Success',
//         message: '登录成功!',
//         type: 'success',
//       })
//       loginEvn.forEach(evn => evn.call)
//       return Promise.resolve(res)
//     }).catch((e: any) => {
//       return Promise.reject(e)
//     })
//   }

//   const clearUserInfo = () => {
//     userInfo.value = undefined
//   }

//   const logout = async () => {
//     clearUserInfo()
//     ElNotification({
//       title: 'Success',
//       message: '账号已退出',
//       type: 'success',
//     })
//     return Promise.resolve()
//   }

//   function loginEvnRegister(evn: Function) {
//     loginEvn.push(evn)
//   }

//   return {
//     login, logout, profile, hasLogin,
//     clearUserInfo, setUserInfo, fingerPrintInit, loginEvnRegister
//   }
// }

// export const useBaseStore = defineStore('user', storeSetup, { persist: true })
