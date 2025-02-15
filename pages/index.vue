<template>
  <div>
    <el-table :data="page.tableData" border style="width: 100%">
      <el-table-column prop="date" label="名称" width="180" />
      <el-table-column prop="name" label="描述" width="180" />
      <el-table-column prop="address" label="Address" />
    </el-table>

    <el-card class="m-5">
      <div class="font-1 mb-5">{{ page.list }}</div>
      <el-input v-model="page.input" style="max-width: 600px" placeholder="Please input">
        <template #prepend>Http://</template>
      </el-input>

      <el-button class="ml-2" @click="addOne()">添加</el-button>
    </el-card>

    <button class="cy-btn" onclick="my_modal_1.showModal()">open modal</button>
    <dialog id="my_modal_1" class="cy-modal">
      <div class="cy-modal-box">
        <h3 class="text-lg font-bold">添加书签!</h3>
        <p class="py-4">Press ESC key or click the button below to close</p>

        <div class="cy-modal-action">
          <button class="cy-btn cy-btn-primary mr-3">添加</button>
          <form method="dialog">
            <button class="cy-btn">退出</button>
          </form>
        </div>
      </div>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksShowAll, bookmarksAddOne } from "~/server/apis";
import type { HomeItem } from "~/server/apis/bookmark/typing";

const page = reactive<{
  tableData: any;
  list: HomeItem | undefined;
  input: string;
}>({
  tableData: [],
  list: undefined,
  input: "",
});

onMounted(() => {
  queryData();
});

function addOne() {
  bookmarksAddOne({ url: page.input });
}

function queryData() {
  bookmarksShowAll().then((res) => {
    page.list = res;
  });

  page.tableData = [
    {
      date: "2016-05-03",
      name: "Tom",
      address: "No. 189, Grove St, Los Angeles",
    },
    {
      date: "2016-05-02",
      name: "Tom",
      address: "No. 189, Grove St, Los Angeles",
    },
    {
      date: "2016-05-04",
      name: "Tom",
      address: "No. 189, Grove St, Los Angeles",
    },
    {
      date: "2016-05-01",
      name: "Tom",
      address: "No. 189, Grove St, Los Angeles",
    },
  ];
}
</script>
<style>
</style>