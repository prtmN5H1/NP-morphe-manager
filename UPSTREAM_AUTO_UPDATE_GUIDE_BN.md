# স্বয়ংক্রিয় Upstream Sync ও APK Build (বাংলা নির্দেশিকা)

এই Fork-এ `.github/workflows/custom-manager-build.yml` প্রতিদিন MorpheApp/morphe-manager-এর `main` branch পরীক্ষা করে। নতুন official commit থাকলে সেটি custom `main`-এ merge করে push করার চেষ্টা করে। Merge সফল হলে Community Catalog-সহ Debug APK তৈরি করে GitHub Actions Artifact হিসেবে রাখে। `workflow_dispatch` দিয়ে হাতে-ও চালানো যায়।

## একবারের সেটআপ
1. GitHub-এ `MorpheApp/morphe-manager`-এর নিজের Fork তৈরি করুন।
2. এই ZIP-এর সম্পূর্ণ source আপনার Fork-এর `main` branch-এ upload/push করুন।
3. Fork-এর **Settings → Actions → General → Workflow permissions**-এ `Read and write permissions` দিন।
4. **Actions** tab-এ workflow enable করুন। `Custom Morphe Manager - upstream sync and APK build` চালাতে `Run workflow` চাপতে পারেন।
5. Build সফল হলে Actions → সংশ্লিষ্ট run → Artifacts থেকে APK নামিয়ে নিন।

## কী স্বয়ংক্রিয় হবে
- প্রতিদিন upstream `main` branch check।
- নতুন official commits custom branch-এ merge করার চেষ্টা।
- merge সফল হলে APK build ও artifact upload।
- আপনার Community Catalog পরিবর্তন upstream merge-এর পরেও থাকবে, যতক্ষণ upstream একই অংশে conflicting change না আনে।

## Conflict হলে
Workflow merge abort করবে, custom branch-এ কিছু push করবে না এবং APK build করবে না। GitHub Actions run-এ conflict-এর কারণ দেখাবে। তখন সংশ্লিষ্ট ফাইলের পরিবর্তন মিলিয়ে একবার conflict resolve করতে হবে। শতভাগ conflict-free automatic merge কোনো Git workflow নিশ্চিত করতে পারে না।

## গুরুত্বপূর্ণ সীমাবদ্ধতা
- এই workflow official `main` branch অনুসরণ করে, `dev` নয়। Morphe-র contribution guide অনুযায়ী development `dev` branch-এ হয়; stable/release updates-এর জন্য `main` বেছে নেওয়া হয়েছে।
- এখনকার workflow **Debug APK** তৈরি করে। এটি স্বয়ংক্রিয়ভাবে আপনার ফোনে install/update করবে না।
- Official Morphe APK-এর signing key আপনার কাছে নেই; custom build সাধারণত official APK-এর ওপর সরাসরি update হিসেবে install হবে না। Custom APK-র পরবর্তী update একই signing key দিয়ে sign করতে হবে। স্থায়ী signed release workflow-এর জন্য ব্যক্তিগত keystore ও তার password GitHub Actions Secrets-এ নিরাপদে সেট করতে হবে। Keystore বা password কখনও repository-তে commit করবেন না।
- এই workflow APK Artifact দেয়; এখনো GitHub Release-এ স্বয়ংক্রিয় প্রকাশ করে না।
- Community Catalog-এর WebView ও duplicate detection বাস্তব Android build/device-এ পরীক্ষা করা বাকি। আগের build প্রচেষ্টা Gradle distribution download-এ network error-এ থেমেছিল; Kotlin compile সফল হয়েছে বলে দাবি করা হচ্ছে না।
