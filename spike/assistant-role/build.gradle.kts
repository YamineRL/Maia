// A throwaway spike. Deliberately not a module of maia-app: M3 brief section
// 0.1 wants it outside the product tree so it can be deleted without a
// conversation, and so M2's edits to :app never collide with it.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
