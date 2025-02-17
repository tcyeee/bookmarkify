import { defineStore } from 'pinia'

export const sysBaseStore = defineStore('sys', {
    persist: true,
    state: () => <{
        sysEvents: Map<string, Map<string, KeyEvent>> | undefined
        dialogLoginManage: Set<DialogStatus> | undefined
    }>({
        sysEvents: undefined,
        dialogLoginManage: undefined
    }),
    actions: {

        /**
         * 获取当前dialog状态
         * 
         * @param dialogName 查询的窗口
         * @returns status
         */
        checkDialogStatus(dialogName: DialogStatus | string): boolean {
            const status: DialogStatus = DialogStatus[dialogName as keyof typeof DialogStatus]
            return this.dialogLoginManage == undefined ? false : this.dialogLoginManage.has(status)
        },

        /**
         * 修改当前dialog状态
         * 
         * @param dialogName 查询的窗口
         * @param status 修改的状态
         */
        updateDialogStatus(dialogName: DialogStatus, status?: boolean): void {
            if (this.dialogLoginManage == undefined) throw new Error
            if (status && status == true) {
                this.dialogLoginManage.add(dialogName)
            } else {
                this.dialogLoginManage.delete(dialogName)
            }
        },

        /**
         * 触发键盘事件
         * 
         * @param keyCode 触发按键
         * @param path 当前页面
         */
        triggerKeyEvent(keyCode: string, path: string) {
            if (!this.sysEvents || !this.sysEvents.has(keyCode)) return;
            const events: Map<string, KeyEvent> = this.sysEvents.get(keyCode) || new Map();
            if (!events.has(keyCode + path)) return;
            const info: KeyEvent | undefined = events.get(keyCode + path) || undefined;
            // 触发对应的方法
            info?.triggerFunc.call(null);
        },

        /* 获取按键关联键盘事件 */
        getKeyEvent(keyCode: string): Map<string, KeyEvent> | undefined {
            if (!(this.sysEvents instanceof Map)) this.sysEvents = new Map();
            if (!this.sysEvents.has(keyCode)) return undefined;
            return this.sysEvents.get(keyCode);
        },

        /* 存入键盘事件 */
        setKeyEvent(keyCode: string, events: Map<string, KeyEvent>) {
            if (!this.sysEvents) this.sysEvents = new Map();
            this.sysEvents.set(keyCode, events);
            console.log(`为${keyCode}绑定一个键盘事件,当前总事件:${this.sysEvents.size}`);
        },

        /**
         * 注册键盘事件
         * 
         * @param keyCode 触发条件1: 按键 
         * @param currentPath 触发条件2: 所属页面
         * @param triggerFunc 触发方法
         */
        registerKeyEvent(keyCode: string, currentPath: string, triggerFunc: Function) {
            const events = this.getKeyEvent(keyCode) || new Map();
            events.set(keyCode + currentPath, { currentPath, triggerFunc });
            this.setKeyEvent(keyCode, events)
        }
    }
})