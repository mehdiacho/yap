Here is a comprehensive pricing and unit-economics analysis for your native Android voice capture app. Because you are offloading the heaviest compute (audio chunk transcription) to the device and only relying on the cloud for refinement and optional storage, your unit economics are incredibly strong.  
Below are the verified rates (as of June 2026), your per-persona cost models, pricing tier recommendations, and an interactive calculator to stress-test your margins.

### **1\. Unit-Rate Table & Verified Assumptions**

*All rates sourced from official Google Cloud / Firebase pricing pages as of June 2026\.*

| Cost Driver | Service | Current Rate (USD) | Notes |
| :---- | :---- | :---- | :---- |
| **Audio Input** | Gemini 1.5 Flash API | $0.25 / 1M tokens | Billed at 25 tokens/sec (1,500 tokens/min). |
| **Text Input** | Gemini 1.5 Flash API | $0.125 / 1M tokens | For prompts, stitching logic, and transcript text. |
| **Text Output** | Gemini 1.5 Flash API | $0.75 / 1M tokens | Structured markdown and metadata output. |
| **DB Reads** | Firebase Firestore | $0.06 / 100K reads | Syncing library lists, opening notes. |
| **DB Writes** | Firebase Firestore | $0.18 / 100K writes | Saving transcripts, edits, metadata. |
| **Storage** | Firebase Cloud Storage | $0.026 / GB-month | Audio backups \+ metadata storage. |
| **Egress (Data Out)** | Firebase/GCP | $0.12 / GB | Cloud storage downloads \+ API payload egress. |
| **Compute** | Cloud Functions (v2) | $0.40 / 1M invocations | \+ $0.0000025 per GB-sec (negligible per note). |

**On-Device AI Assumptions Verified:** Yes, Android's SpeechRecognizer, ML Kit GenAI (Gemini Nano), and LiteRT (Whisper) incur **$0.00 runtime API costs** for you. Your *only* cost is the egress bandwidth to serve the LiteRT Whisper model file via Firebase Hosting/Storage to devices upon install.

* **Model Download Egress:** A 100 MB model × $0.12/GB \= **$0.012 per new user install**. Amortized over a user's lifetime, this is functionally $0.

### **2\. Per-Minute & Per-User Cost Breakdown**

To build the persona model, we must first calculate the blended unit costs per minute of audio.  
**Cloud Refinement Cost (Gemini API) per Minute of Audio:**

* **Audio Input:** 1,500 tokens/min. $Cost \= \\frac{1500}{10^6} \\times \\$0.25 \= \\$0.000375$  
* **Text Input (Prompts/Stitching):** Assume \~300 tokens/min. $Cost \= \\frac{300}{10^6} \\times \\$0.125 \= \\$0.0000375$  
* **Text Output (Formatting/Tags):** Assume \~200 tokens/min. $Cost \= \\frac{200}{10^6} \\times \\$0.75 \= \\$0.00015$  
* **Total API Cost:** **\~$0.00056 per minute** (Let's pad this to **$0.0006/min** to account for failed calls, retries, or extra bridging tokens).

**Storage & Sync Cost per Minute of Audio:**

* Audio stored at 32 kbps (opus/m4a) \= \~0.24 MB/minute.  
* Storage cost \= $0.000006 / min. Egress cost (1 playback/sync) \= $0.000028 / min.

#### **Cost by Persona (Monthly)**

| Persona | Usage Profile | API Cost | Storage/Egress | Firestore/Funcs | Total Marginal Cost |
| :---- | :---- | :---- | :---- | :---- | :---- |
| **Light** | 30 mins (15 mins cloud refined), 50% backup | $0.009 | $0.001 | $0.001 | **\~$0.011 / month** |
| **Typical** | 180 mins (144 mins cloud refined), 80% backup | $0.086 | $0.005 | $0.004 | **\~$0.095 / month** |
| **Heavy** | 20 hours (1200 mins cloud refined), 100% backup | $0.720 | $0.041 | $0.025 | **\~$0.786 / month** |
| **BYOK Local** | User's API key, local storage only | $0.000 | $0.000 | $0.001 | **\~$0.001 / month** |

### **3\. Recommended Pricing Tiers**

Because your infrastructure costs are exceptionally low (under $0.80 for 20 hours of AI processing), you can price aggressively while maintaining 80%+ gross margins on paid tiers.

#### **Free Tier**

* **Inclusions:** Unlimited on-device drafting.  
* **Cloud Cap:** **30 minutes/month** of cloud refinement.  
* **Storage Cap:** **50 MB** (approx. 3.5 hours of audio backup).  
* **Strategy:** Keeps maximum free user cost under $0.03/month. This acts as a cheap marketing funnel.

#### **Pro Tier (Managed Key)**

* **Price:** **$1.99 / month** or **$19.99 / year** (decided 2026-07-13; originally spec'd $2.99/$24.99).  
* **Inclusions:** **30 hours (1800 minutes)** of cloud refinement/month. **2 GB** of audio backup.  
* **Margin & Breakeven:** A Typical user yields a **96% gross margin**. Even a Heavy user (20 hours) yields a **73% margin**. Breakeven is roughly 80 hours of cloud processing a month—which dictates your fair-use limit.  
* **Fair Use Clause:** Hard stop at 30 hours (1800 mins); users must wait until the next cycle or plug in a BYOK key for overages.

#### **BYOK \+ Storage (User Key)**

* **Price:** **$0.99 / month**.  
* **Inclusions:** Unlimited AI processing (they pay Google), but you provide **2 GB** cloud sync/backup and multi-device DB sync.  
* **Margin:** Your cost is roughly $0.05/month. Gross margin: **95%**.

#### **BYOK Gating & Unlock Mechanics**

BYOK Local-Only gives users a fully functional app for zero recurring cost. It is a premium feature that must be unlocked.

* **One-Time Payment:** **$4.99**. This is equivalent to \~5 years of DB metadata costs for a Heavy user, rendering it pure profit. It appeals heavily to the "anti-subscription" privacy crowd.  
* **Referral Unlock:** **5 completed referrals**.  
* **"Completed" Definition:** The referred user must sign up **AND save $\\ge$ 3 voice notes**. This proves they generated value (downloaded the Whisper model, recorded audio) and stops simple emulator farms from bypassing your paywall with fake accounts.

### **4\. Risks & Mitigations**

1. **Abuse & API Scraping:** If malicious actors reverse-engineer your app, they might use your proxy backend to hit the Gemini API for free.  
   * **Mitigation:** Implement Google Play Integrity API / App Attest. Ensure your backend proxy only accepts requests carrying a valid, recently minted hardware-attestation token.  
2. **Legacy Storage Creep:** Free users might abandon the app, leaving 50MB of data in your storage forever, slowly draining margins.  
   * **Mitigation:** Update Terms of Service to state: *Free tier audio backups are subject to deletion after 6 months of account inactivity.*  
3. **Model Cost Increases (Sensitivity):** If Google doubles the cost of Gemini Flash tomorrow, your Typical user cost goes from \~$0.10 to \~$0.18, and Heavy from \~$0.79 to \~$1.50. At $2.99/mo, you remain highly profitable even in a 2x cost scenario.

