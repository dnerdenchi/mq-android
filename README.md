# BondVPN for Android

[![Latest Release](https://img.shields.io/github/v/release/dnerdenchi/mq-android?label=Release&color=blue)](https://github.com/dnerdenchi/mq-android/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(arm64)-3DDC84?logo=android&logoColor=white)](#動作環境)
[![Engine](https://img.shields.io/badge/Engine-mqvpn%20v0.18.0-orange)](https://github.com/mp0rta/mqvpn)

**BondVPN** は、Wi-Fi・モバイル回線（SIM）・Bluetooth／USB テザリング・有線イーサネットなど、複数の通信経路を 1 本に束ねて高速化・冗長化する Android 向けマルチパス VPN クライアントです。

バックエンドに [mqvpn](https://github.com/mp0rta/mqvpn)（MASQUE CONNECT-IP / Multipath QUIC）を採用し、通信中の回線切り替えや複数回線の同時集約（ボンディング）、さらには周囲のスマートフォンとの回線共有（Pair & Share）までを 1 つのアプリで完結できます。

---

## 主な機能

- **マルチパス回線ボンディング（帯域集約・冗長化）**
  - Wi-Fi、SIM（モバイルデータ）、Bluetooth テザリング、USB テザリング、イーサネットを同時に束ねて通信。
  - `WLB`（帯域集約）スケジューラ、Hybrid TCP レーン、UDP 443 Reorder により、動画視聴や大容量ダウンロードでも複数経路を無駄なく活用します。
- **Pair & Share / PairBond（複数端末での回線シェア・合算）**
  - 同じ Wi-Fi 上の別端末と 6 桁コードで簡単にペアリングし、互いの SIM 回線を暗号化中継（AES-GCM）でシェア。
  - 自端末の Wi-Fi + SIM に加え、複数のペア端末の SIM 回線まで 1 つのセッションに束ねる **PairBond** に対応。
- **AdGuard 連携 & ローカルプロキシモード**
  - Android 標準の VPN モードだけでなく、AdGuard 等と併用できる SOCKS5 / HTTP CONNECT ローカルプロキシモードを搭載。
  - AdGuard の自動化機能と連携し、BondVPN の接続・切断に合わせて AdGuard の送信プロキシを自動で ON / OFF できます。
- **きめ細かな帯域幅管理・利用状況グラフ**
  - 経路ごとの「常時速度制限」「月間データ上限」「1日データ上限」を設定可能。
  - リアルタイム速度モニター、回線別の通信量履歴グラフ（最大 1 年間保持）、ワンタップの回線別スピードテスト（Wi-Fi 単体 / SIM 単体 / ボンディング比較）を内蔵。

---

## ダウンロード・インストール

1. [**Releases ページ**](https://github.com/dnerdenchi/mq-android/releases/latest) から最新の APK（`BondVPN-v0.x.0-debug.apk`）をダウンロードしてインストールします。
2. 次回以降は、アプリ内の **「設定 → アップデートを確認」** から SHA-256 検証付きで直接アップデートできます。
3. 更新履歴の詳細は [CHANGELOG.md](CHANGELOG.md) をご覧ください。

### 動作環境

| 項目 | 要件 |
| :--- | :--- |
| **対応 OS** | Android 8.0 (API 26) 以上 |
| **対応 ABI** | `arm64-v8a` |
| **VPN エンジン** | [mqvpn v0.18.0](https://github.com/mp0rta/mqvpn/releases/tag/v0.18.0)（経路別レート制限パッチ適用済み） |

---

## クイックスタート

### 1. サーバーの準備（VPS 等）

Ubuntu / Debian 系のサーバー（VPS 等）で公式インストールスクリプトを実行し、UDP 443 ポートで `mqvpn` サーバーを起動します。

```bash
# Ubuntu 24.04 等で libevent が未導入の場合
sudo apt update && sudo apt install -y libevent-2.1-7t64 libevent-pthreads-2.1-7t64

# mqvpn サーバーのインストールと起動 (UDP 443)
curl -fsSL https://github.com/mp0rta/mqvpn/releases/latest/download/install.sh \
  | sudo bash -s -- --start --port 443 --subnet 10.8.0.0/24

# ファイアウォールで UDP 443 を開放（VPS 側のセキュリティグループも要開放）
sudo ufw allow 443/udp && sudo ufw reload

# クライアント接続用の認証キーを確認
sudo grep -A3 '^\[Auth\]' /etc/mqvpn/server.conf
```

> [!TIP]
> サーバー側で `WLB`・`Hybrid`・`UDP 443 Reorder` を一括有効化する場合は、リポジトリ付属の `sudo ./server/enable-mqvpn-wlb-hybrid-reorder.sh` を実行すると安全に適用できます。

### 2. アプリの接続設定

BondVPN アプリを開き、プロファイルに以下の項目を入力して接続します。

| 設定項目 | 内容 |
| :--- | :--- |
| **サーバーアドレス** | 構築したサーバーの IP アドレスまたはドメイン名 |
| **ポート番号** | `443`（サーバーで設定した UDP ポート） |
| **mqvpn 認証キー** | `/etc/mqvpn/server.conf` の `[Auth] Key` の値 |
| **自己署名証明書を許可** | 公式スクリプトの標準証明書を使う場合は **ON**（Let's Encrypt 等の場合は OFF） |

---

## 応用機能の使い方

<details>
<summary><b>AdGuard との自動連携（プロキシモード）</b></summary>

AdGuard の VPN 機能を有効にしたまま BondVPN でマルチパス通信を行う手順です。

1. AdGuard の **「設定 → 一般設定 → 詳細設定 → 自動化」** を ON にし、AdGuard のアプリ管理で **BondVPN を保護対象から除外** します。
2. AdGuard の送信プロキシに SOCKS5 `127.0.0.1:1080`（BondVPN 設定画面のポート番号）を登録します。
3. BondVPN の設定で **「AdGuard 自動切替」** を ON にし、AdGuard 側に表示される自動化パスワードを保存します。
4. 接続開始時に AdGuard のプロキシが自動で ON になり、切断時やアプリ終了時には自動で OFF に戻ります。

</details>

<details>
<summary><b>Pair & Share / PairBond（複数端末での回線共有）</b></summary>

同じ Wi-Fi 上にいる別の Android 端末の SIM 回線を束ねて利用できます。

- **基本共有（Pair & Share）**: 両端末を同じ Wi-Fi に接続して Pair & Share を有効化し、6 桁コードでペアリング・承認するだけで LAN 内暗号化中継が利用できます。
- **複数端末ボンディング（PairBond）**: サーバーに [server/pairbond-relay](server/pairbond-relay/README.md)（TCP 443）を配備すると、自端末の Wi-Fi + SIM と複数のペア端末 SIM を同時にチャンク分散・順序復元して通信できます。各ペア端末には「自動ボンディング」「バックアップ専用」「使用しない」を設定可能です。

</details>

<details>
<summary><b>サーバーの運用・アンインストールコマンド</b></summary>

```bash
# 稼働状態とログの確認
sudo systemctl status mqvpn-server --no-pager
sudo journalctl -u mqvpn-server -f

# 設定変更後の再起動
sudo nano /etc/mqvpn/server.conf
sudo systemctl restart mqvpn-server

# アンインストール
curl -fsSL https://github.com/mp0rta/mqvpn/releases/latest/download/install.sh \
  | sudo bash -s -- --uninstall
```

</details>

---

## 開発・ビルド（コントリビューター向け）

<details>
<summary><b>ローカルでのビルド手順・ディレクトリ構成</b></summary>

### ビルドとテスト

JDK 17（または Android Studio 付属の JBR）と Gradle を使用してビルドします。

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
gradle test assembleDebug
```

生成される APK: `app/build/outputs/apk/debug/app-debug.apk`

### 主要コンポーネント構成

| パス | 役割 |
| :--- | :--- |
| `app/src/main/java/com/example/glorytun/MqvpnBondingService.kt` | VPN サービス制御・UI への状態通知 |
| `app/src/main/java/com/example/glorytun/MqvpnConfigFactory.kt` | 接続プロファイルから `mqvpn` 設定を生成 |
| `app/src/main/java/com/example/glorytun/AppUpdateManager.kt` | GitHub Releases 連携のアプリ内アップデーター |
| `app/src/main/java/com/mqvpn/sdk/` | `mqvpn` Android SDK ソースコード |
| `app/src/main/jniLibs/arm64-v8a/libmqvpn_jni.so` | `mqvpn` ネイティブブリッジ（`native-patches/` 適用済み） |
| `server/` | サーバー設定補助スクリプトおよび PairBond リレー |

### リリース運用

- 安定版リリース時は `app/build.gradle` の `versionCode` / `versionName`（`0.a.b` 形式）および [CHANGELOG.md](CHANGELOG.md) を更新し、リリースノート末尾に `<!-- updater-version-code: <versionCode> -->` を記載して GitHub Releases に APK を添付します。
- 開発版（`development` タグ）は `-PdevelopmentBuild=true` を付与してビルドし、単一の Pre-release 上の APK を差し替えて運用します。

</details>
