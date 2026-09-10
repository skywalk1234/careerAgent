# Taste
- Communicates in Simplified Chinese; expects replies and UI copy in Chinese. Confidence: 0.9
- Describes frontend changes as concrete UI flows (which control, what happens on click, where results render), citing exact component class names (e.g. `el-segmented`, `el-card__body`). Confidence: 0.7
- Prefers reusing existing backend APIs when moving or refactoring a feature, rather than adding new endpoints (explicitly asked to "走现有的推荐岗位接口"). Confidence: 0.7
- Prefers consolidating features into in-page segmented tab controls (el-segmented) and removing redundant standalone pages to simplify navigation. Confidence: 0.6
- Expects new views to follow existing interaction patterns in the app, e.g. list on the left / details in the right panel. Confidence: 0.5
- Project stack: Vue 3 + Element Plus frontend (career-planner-web for a career-planning app). Confidence: 0.8
