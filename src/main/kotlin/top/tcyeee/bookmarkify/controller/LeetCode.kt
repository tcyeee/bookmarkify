package top.tcyeee.bookmarkify.controller

/**
 * LeetCode Test
 *
 * @author tcyeee
 * @date 3/10/25 14:22
 */
class LeetCode

fun main() {
    val params: IntArray = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    val result = sortArrayByParity(params)
    println("result: $result params: ${params.contentToString()}")
}

fun sortArrayByParity(nums: IntArray): IntArray {
    var end = nums.size - 1
    var temp: Int
    for (start in nums.indices) {
        if (start >= end) break
        println("curren index: $start, current value: ${nums[start]}")

        if (nums[start] % 2 == 0) continue
        for (j in end downTo start) {
            if (nums[end] % 2 != 0) {
                temp = nums[start]
                nums[start] = nums[end]
                nums[end] = temp
                end--
                break
            }
            end--
        }
    }
    return nums
}