import { createApp } from "vue";
import { createPinia } from "pinia";
import "@vue-flow/core/dist/style.css";
import "./style.css";
import App from "./App.vue";
import { startFaviconAnimation } from "./favicon";
createApp(App).use(createPinia()).mount("#app");
startFaviconAnimation();
