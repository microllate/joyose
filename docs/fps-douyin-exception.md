# FPS Hook: Douyin exception

Base: `d326a9e6145a6ca12a4b51d7a522534c64613f15`.

Only change: exclude Douyin (`com.ss.android.ugc.aweme`) from the 60 FPS -> 120 FPS rewrite.

- Douyin 60 FPS stays 60 FPS.
- Other packages keep the existing 60 FPS -> 120 FPS behavior.
- Other FPS values remain unchanged.
- Joyose cloud-control hook is unchanged.
- No GPU/perflock diagnostic hooks are included.
