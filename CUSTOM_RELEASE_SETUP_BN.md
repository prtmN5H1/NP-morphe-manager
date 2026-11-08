# Custom Manager Release + Persistent Signing — বাংলা নির্দেশিকা

## এই পরিবর্তনে কী বদলেছে
- Manager self-update metadata এখন শুধু `prtmN5H1/NP-morphe-manager`-এর `main` ও `dev` branch-এর `app-release.json` থেকে পড়বে। পুরোনো বা ভুল করে থাকা Official Morphe APK URL অ্যাপ গ্রহণ করবে না।
- About পেজে আপনার Custom Project GitHub এবং Releases link থাকবে; Original Morphe attribution-ও থাকবে।
- Official Morphe Manager-এর FCM update topics থেকে অ্যাপ unsubscribe করবে। আপনার Manager update নিজের GitHub Release থেকে WorkManager দিয়ে পরীক্ষা হবে। Background notification চালু থাকলে check interval সব ডিভাইসেই কাজ করবে। Morphe patch-bundle-এর FCM notification আগের মতো থাকবে।
- প্রতিটি Source card-এ ডাউনলোড হওয়া `patches.jar`-এর প্রকৃত সাইজ দেখাবে। Sources Sort menu-তে Smallest source first এবং Largest source first থাকবে; ডাউনলোড না হওয়া Source দুটো Sort-এই শেষে থাকবে।
- Experimental app versions-এর global default Settings > Advanced > Updates-এ থাকবে। নতুন Source Add করার সময় সেই default আগে থেকে নির্বাচিত হবে; Add dialog-এ আলাদা করে override করা যাবে। প্রতিটি Source card-এর নিজস্ব Experimental switch-ও থাকবে।
- Community Catalog-এর Add link থেকে App/Universal scope Queue-তে রাখা ও Native Add flow-তে পাঠানো হবে। একই Source duplicate হলে Native flow অনুযায়ী validate হবে।

## স্থায়ী Signing Key একবার তৈরি করুন (Windows PC)
**এই অংশটি PR Merge করার আগে সম্পন্ন করুন**, কারণ `release.yml` main/dev-এ push হলে signed release build চালাবে। Secrets না থাকলে workflow নিরাপত্তার জন্য ব্যর্থ হবে।

1. Repository root খুলুন (`NP-morphe-manager`)।
2. PowerShell খুলে চালান: `./create-custom-keystore.ps1`
3. Alias, keystore password ও key password নির্বাচন করুন এবং নিরাপদে লিখে রাখুন।
4. Script `app/keystore.jks` এবং `KEYSTORE_B64.txt` তৈরি করবে।
5. `app/keystore.jks`-এর অন্তত ২টি offline backup রাখুন (যেমন USB এবং encrypted backup)।
6. GitHub Repository → Settings → Secrets and variables → Actions → New repository secret-এ এই চারটি Secret তৈরি করুন:
   - `KEYSTORE_B64` = `KEYSTORE_B64.txt`-এর সম্পূর্ণ content
   - `KEYSTORE_PASSWORD` = keystore password
   - `KEYSTORE_ENTRY_ALIAS` = আপনার alias
   - `KEYSTORE_ENTRY_PASSWORD` = key password
7. `app/keystore.jks` এবং `KEYSTORE_B64.txt` কখনো Commit/Push করবেন না। `.gitignore`-এ এগুলো বাদ রাখা হয়েছে।

## নিজের Release কীভাবে হবে
- `release.yml` `main` ও `dev` branch-এ push হলে semantic-release চালায়।
- নতুন Feature-এর PR title `feat: ...` এবং Bug fix-এর PR title `fix: ...` দিয়ে শুরু করুন। Pull Request Merge করার সময় **Squash and merge** বেছে নিন, যাতে semantic-release conventional commit পড়ে সঠিক version বাড়াতে পারে।
- `main` stable Release; `dev` prerelease Release। `dev` branch না থাকলে dev channel থেকে Release হবে না।
- Workflow স্থায়ী Keystore দিয়ে Release APK sign করবে এবং আপনার GitHub Releases-এ প্রকাশ করবে।
- APK পাওয়া যাওয়ার পর `app-release.json` আপনার নিজের branch-এ update হবে।
- Manager update code এই raw URL-গুলো ব্যবহার করবে:
  - Stable: `https://raw.githubusercontent.com/prtmN5H1/NP-morphe-manager/refs/heads/main/app-release.json`
  - Pre-release: `https://raw.githubusercontent.com/prtmN5H1/NP-morphe-manager/refs/heads/dev/app-release.json`
- Official Morphe Manager update topic থেকে কোনো Manager update notification নেওয়া হবে না। Background update notification চালু থাকলে WorkManager আপনার fork-এর release metadata পরীক্ষা করবে। অ্যাপ খুললেও in-app update check একই Custom Release source ব্যবহার করবে।

## গুরুত্বপূর্ণ Package/Signature সীমা
- Release APK-এর application ID এখনও `app.morphe.manager`; Debug APK-তে `.debug` suffix থাকে। তাই Debug APK ও Release APK Android-এর চোখে আলাদা app।
- আপনার নতুন Keystore দিয়ে প্রথম Release APK-টি একই package ID-র Official-signed Morphe Manager-এর ওপর Update হিসেবে install করা যাবে না। Official Manager আগে থেকে থাকলে, গুরুত্বপূর্ণ app data/settings backup নিয়ে সেটি uninstall করে প্রথম Custom-signed Release install করতে হবে। আগের Debug build-টিও Release APK-এর Update হবে না; আলাদা `.debug` app হিসেবে থাকবে।
- প্রথম Custom-signed Release install হওয়ার পর ভবিষ্যতের Custom Release একই Key দিয়ে sign হবে, তাই পরের আপডেটগুলো একই package-এ Update হিসেবে install করা যাবে।
- Keystore হারালে ভবিষ্যতের APK-কে আগের Custom install-এর Update হিসেবে গ্রহণ করানো যাবে না। তাই Keystore-এর backup অত্যন্ত জরুরি।

## যাচাই
- `Build Community Catalog APK` হলো Debug artifact—এটি Custom Release signing-এর বিকল্প নয়।
- `Release` workflow সফল এবং GitHub Releases-এ APK প্রকাশিত হওয়ার পরেই in-app self-update পরীক্ষা করুন।
- Keystore Secret না থাকলে Release workflow নিরাপত্তার জন্য ব্যর্থ হবে; debug key দিয়ে ভুলভাবে Release প্রকাশ করবে না।
- APK Download করা গেলেও Android Install confirmation চাইতে পারে; সম্পূর্ণ নীরব Install-এর নিশ্চয়তা নেই।
