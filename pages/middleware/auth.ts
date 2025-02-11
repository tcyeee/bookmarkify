export default defineNuxtRouteMiddleware((to, from) => {
    console.log("==middleware==");

    // const isLoggedIn = !!localStorage.getItem('user_token');
    // if (!isLoggedIn && to.path !== '/login') {
    // return navigateTo('/login');
    // }
})
