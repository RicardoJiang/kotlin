
// FILE: test.kt
fun box() {
    val k = if (getA()
        && getB()
        && getC()
        && getD()) {
        true
    } else {
        false
    }
}

fun getA() = true

fun getB() = true

fun getC() = false

fun getD() = true

// EXPECTATIONS JVM_IR
// test.kt:4 box
// test.kt:14 getA
// test.kt:4 box
// test.kt:5 box
// test.kt:16 getB
// test.kt:5 box
// test.kt:6 box
// test.kt:18 getC
// test.kt:6 box
// test.kt:10 box
// test.kt:4 box
// test.kt:12 box

// EXPECTATIONS JS_IR
// test.kt:4 box
// test.kt:14 getA
// test.kt:5 box
// test.kt:16 getB
// test.kt:6 box
// test.kt:18 getC
// test.kt:10 box
// test.kt:4 box
// test.kt:12 box

// EXPECTATIONS WASM
// test.kt:4 $box (16)
// test.kt:14 $getA (13, 17)
// test.kt:4 $box (16)
// test.kt:5 $box (11)
// test.kt:16 $getB (13, 17)
// test.kt:5 $box (11)
// test.kt:6 $box (11)
// test.kt:18 $getC (13, 18)
// test.kt:6 $box (11)
// test.kt:7 $box (11)
// test.kt:10 $box (8)
// test.kt:4 $box (12)
// test.kt:12 $box (1)
