# India Starter Feed Catalog (RSS/Atom)

**Purpose:** a bundled, pre-verified feed catalog for a zero-cost, on-device news aggregator so it is useful on day one anywhere in India.
**Verification date:** 2026-09-13 (IST) · **Method:** live `curl` HTTP GET (Chrome UA, redirects followed, 25 s timeout) + `xmllint` well-formedness + item-count snapshot. No API keys, no JS, no paywalls.
**Coverage:** all **36 states and union territories** have at least one verified feed. 170 catalog entries tested; **169 returned HTTP 200 with well-formed XML**; 1 returns 200 but malformed XML; 2 of the valid feeds are empty at check time.

---

## TL;DR — best and worst covered

**National (English):** 16 verified feeds incl. The Hindu, Indian Express, Times of India, Hindustan Times, NDTV (via FeedBurner), Mint, Business Standard, Economic Times, India Today, Firstpost, Deccan Herald, News18, The Hindu BusinessLine, The Federal. Plus 2 Hindi national feeds (Aaj Tak, News18 Hindi).

**Backbone:** The Hindu publishes a verified state feed for **all 36 states/UTs** (`/news/national/<state>/feeder/default.rss`, with Delhi/Puducherry under `/news/cities/...`). This guarantees English state coverage everywhere even where no local outlet has a feed.

**Best-covered (rich English + strong local-language + multiple outlets):**
1. **Kerala** — Mathrubhumi, Asianet Malayalam, OnManorama, News18 Malayalam (all Malayalam).
2. **Karnataka** — Prajavani, Kannada Prabha, Asianet Kannada, News18 Kannada + Deccan Herald (English). *Critical: Google News has no Kannada edition.*
3. **Odisha** — News18 Odia, Odisha Bhaskar, OdishaTV, Dharitri, Kalinga TV (Odia). *Critical: Google News has no Odia edition.*
4. **Maharashtra** — News18 Marathi, Maharashtra Times, Pudhari, Saamana.
5. **Tamil Nadu** — Hindu Tamil Thisai, Vikatan, Puthiya Thalaimurai, News18 Tamil.
6. **Telangana / Andhra Pradesh** — News18 Telugu (state feeds), NTV, TV9, 10TV, Namasthe Telangana, Telangana Today, Siasat.
7. **Assam** — Asomiya Pratidin (Assamese) + Assam Tribune, Time8, Sentinel, Northeast Now. *Critical: Google News has no Assamese edition.*
8. **Gujarat** — News18 Gujarati, Divya Bhaskar, TV9 Gujarati, Dainik Bhaskar Gujarat.

**Worst-covered (thin, mostly English-only, small/community outlets):**
- **Lakshadweep** — only The Hindu + one small local (Lakshadweep Times). No established daily.
- **Dadra & Nagar Haveli and Daman & Diu** — only The Hindu + two small Hindi/Gujarati portals. No established daily.
- **Puducherry** — The Hindu + News18 Tamil Puducherry + News Today; no dedicated Puducherry daily feed.
- **Andaman & Nicobar** — The Hindu + Andaman Sheekha.
- **Chandigarh** — The Hindu + Hindi-belt feeds; The Tribune is Akamai-blocked.
- **Punjab** — only News18 Punjabi for Punjabi; Ajit/Jagbani/Punjab Kesari/PTC have no usable feed.
- **Arunachal, Manipur, Meghalaya, Mizoram, Nagaland, Sikkim, Tripura** — English-only in practice; no usable Khasi/Garo/Mizo/Meitei/Nepali/Kokborok feeds found.

---

## Legend

| Mark | Meaning |
|---|---|
| `verified (200+XML)` | HTTP 200 **and** well-formed XML (RSS/Atom) at check time |
| `DEAD/malformed` | reachable but not usable as-is |
| `[unverified]` | could not be tested (none in the main tables) |
| Level `national` / `state` / `city` / `regional` | intended coverage scope |
| Items | number of `<item>`/`<entry>` elements in the sample fetch (volatile) |

> Notes on sparse feeds are inline. Two feeds are valid XML but returned **0 items** (`The News Minute`, `Live Hindustan Uttar Pradesh`) — do not bundle these.

---

# 1. National feeds

### National (English + Hindi)

| State/UT | Outlet | Feed URL | Language | Level | Verified | Notes |
|---|---|---|---|---|---|---|
| National | The Hindu (Top Stories) | `https://www.thehindu.com/feeder/default.rss` | English | national | verified (200+XML) | - |
| National | The Hindu (National) | `https://www.thehindu.com/news/national/feeder/default.rss` | English | national | verified (200+XML) | - |
| National | The Indian Express | `https://indianexpress.com/feed/` | English | national | verified (200+XML) | - |
| National | The Indian Express (India) | `https://indianexpress.com/section/india/feed/` | English | national | verified (200+XML) | - |
| National | Times of India (Top Stories) | `https://timesofindia.indiatimes.com/rssfeedstopstories.cms` | English | national | verified (200+XML) | - |
| National | Hindustan Times (India) | `https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml` | English | national | verified (200+XML) | Atom |
| National | NDTV (FeedBurner) | `https://feeds.feedburner.com/ndtvnews-top-stories` | English | national | verified (200+XML) | direct `ndtv.com/rss` is Akamai-blocked |
| National | Mint (Livemint) | `https://www.livemint.com/rss/news` | English | national | verified (200+XML) | - |
| National | Business Standard | `https://www.business-standard.com/rss/home_page_top_stories.rss` | English | national | verified (200+XML) | - |
| National | Economic Times | `https://economictimes.indiatimes.com/rssfeedsdefault.cms` | English | national | verified (200+XML) | - |
| National | India Today | `https://www.indiatoday.in/rss/home` | English | national | verified (200+XML) | - |
| National | Firstpost (India) | `https://www.firstpost.com/commonfeeds/v1/mfp/rss/india.xml` | English | national | verified (200+XML) | - |
| National | Deccan Herald | `https://www.deccanherald.com/feed` | English | national | verified (200+XML) | - |
| National | News18 (India) | `https://www.news18.com/rss/india.xml` | English | national | verified (200+XML) | - |
| National | The Hindu BusinessLine | `https://www.thehindubusinessline.com/feeder/default.rss` | English | national | verified (200+XML) | - |
| National | The Federal | `https://thefederal.com/feed` | English | national | verified (200+XML) | - |
| National | Aaj Tak | `https://www.aajtak.in/rss` | Hindi | national | verified (200+XML) | - |
| National | News18 Hindi | `https://hindi.news18.com/commonfeeds/v1/hin/rss/text.xml` | Hindi | national | verified (200+XML) | - |

---

# 2. State / UT feeds (all 36)

## South India

| State/UT | Outlet | Feed URL | Language | Level | Verified | Notes |
|---|---|---|---|---|---|---|
| Andhra Pradesh | The Hindu (Andhra Pradesh) | `https://www.thehindu.com/news/national/andhra-pradesh/feeder/default.rss` | English | state | verified (200+XML) | - |
| Andhra Pradesh | News18 Telugu (Andhra Pradesh) | `https://telugu.news18.com/commonfeeds/v1/tel/rss/andhra-pradesh.xml` | Telugu | state | verified (200+XML) | - |
| Andhra Pradesh | NTV Telugu | `https://www.ntvtelugu.com/rss` | Telugu | state | verified (200+XML) | - |
| Andhra Pradesh | TV9 Telugu | `https://www.tv9telugu.com/rss` | Telugu | state | verified (200+XML) | - |
| Andhra Pradesh | 10TV | `https://www.10tv.in/rss` | Telugu | state | verified (200+XML) | XML served as text/html |
| Karnataka | The Hindu (Karnataka) | `https://www.thehindu.com/news/national/karnataka/feeder/default.rss` | English | state | verified (200+XML) | - |
| Karnataka | Prajavani | `https://www.prajavani.net/feed` | Kannada | state | verified (200+XML) | sparse (3 items at check) |
| Karnataka | Kannada Prabha | `https://kannadaprabha.com/feed` | Kannada | state | verified (200+XML) | - |
| Karnataka | Asianet News Kannada | `https://kannada.asianetnews.com/rss` | Kannada | state | verified (200+XML) | - |
| Karnataka | News18 Kannada | `https://kannada.news18.com/commonfeeds/v1/kan/rss/latest.xml` | Kannada | state | verified (200+XML) | no `karnataka.xml` slug; use `latest.xml` |
| Karnataka | Deccan Herald | `https://www.deccanherald.com/feed` | English | state | verified (200+XML) | - |
| Karnataka | The News Minute | `https://www.thenewsminute.com/feed` | English | regional (South) | verified (200+XML) | **EMPTY (0 items) — do not bundle** |
| Kerala | The Hindu (Kerala) | `https://www.thehindu.com/news/national/kerala/feeder/default.rss` | English | state | verified (200+XML) | - |
| Kerala | Mathrubhumi | `https://www.mathrubhumi.com/rss` | Malayalam | state | verified (200+XML) | - |
| Kerala | Asianet News Malayalam | `https://www.asianetnews.com/rss` | Malayalam | state | verified (200+XML) | - |
| Kerala | OnManorama (Kerala) | `https://www.onmanorama.com/kerala.feeds.onmrss.xml` | Malayalam | state | verified (200+XML) | manoramaonline.com has no direct feed |
| Kerala | News18 Malayalam (Kerala) | `https://malayalam.news18.com/commonfeeds/v1/mal/rss/kerala.xml` | Malayalam | state | verified (200+XML) | - |
| Tamil Nadu | The Hindu (Tamil Nadu) | `https://www.thehindu.com/news/national/tamil-nadu/feeder/default.rss` | English | state | verified (200+XML) | - |
| Tamil Nadu | Hindu Tamil Thisai | `https://www.hindutamil.in/feed` | Tamil | state | verified (200+XML) | - |
| Tamil Nadu | Vikatan | `https://www.vikatan.com/feed` | Tamil | state | verified (200+XML) | sparse (1 item at check; may paginate) |
| Tamil Nadu | Puthiya Thalaimurai | `https://www.puthiyathalaimurai.com/feed` | Tamil | state | verified (200+XML) | sparse (1 item at check) |
| Tamil Nadu | News18 Tamil (Tamil Nadu) | `https://tamil.news18.com/commonfeeds/v1/tam/rss/tamil-nadu.xml` | Tamil | state | verified (200+XML) | - |
| Telangana | The Hindu (Telangana) | `https://www.thehindu.com/news/national/telangana/feeder/default.rss` | English | state | verified (200+XML) | - |
| Telangana | News18 Telugu (Telangana) | `https://telugu.news18.com/commonfeeds/v1/tel/rss/telangana.xml` | Telugu | state | verified (200+XML) | - |
| Telangana | Telangana Today | `https://telanganatoday.com/feed` | English | state | verified (200+XML) | - |
| Telangana | Namasthe Telangana | `https://www.ntnews.com/rss` | Telugu | state | verified (200+XML) | - |
| Telangana | The Siasat Daily | `https://www.siasat.com/feed` | Urdu/English | state | verified (200+XML) | - |

## West India

| State/UT | Outlet | Feed URL | Language | Level | Verified | Notes |
|---|---|---|---|---|---|---|
| Goa | The Hindu (Goa) | `https://www.thehindu.com/news/national/goa/feeder/default.rss` | English | state | verified (200+XML) | - |
| Goa | The Navhind Times | `https://www.navhindtimes.in/feed` | English | state | verified (200+XML) | - |
| Goa | O Heraldo (Herald Goa) | `https://www.heraldgoa.in/feed` | English | state | DEAD/malformed | 200 but XML malformed (duplicate `xmlns:media`); 10 items; tolerant parsers OK |
| Gujarat | The Hindu (Gujarat) | `https://www.thehindu.com/news/national/gujarat/feeder/default.rss` | English | state | verified (200+XML) | - |
| Gujarat | News18 Gujarati (Gujarat) | `https://gujarati.news18.com/commonfeeds/v1/guj/rss/gujarat.xml` | Gujarati | state | verified (200+XML) | - |
| Gujarat | Divya Bhaskar (My Gujarat) | `https://www.divyabhaskar.co.in/rss-v1--category-1035.xml` | Gujarati | state | verified (200+XML) | - |
| Gujarat | TV9 Gujarati | `https://www.tv9gujarati.com/rss` | Gujarati | state | verified (200+XML) | - |
| Gujarat | Dainik Bhaskar (Gujarat) | `https://www.bhaskar.com/rss-v1--category-2314.xml` | Gujarati | state | verified (200+XML) | - |
| Maharashtra | The Hindu (Maharashtra) | `https://www.thehindu.com/news/national/maharashtra/feeder/default.rss` | English | state | verified (200+XML) | - |
| Maharashtra | News18 Marathi (Maharashtra) | `https://marathi.news18.com/commonfeeds/v1/mar/rss/maharashtra.xml` | Marathi | state | verified (200+XML) | - |
| Maharashtra | Maharashtra Times | `https://maharashtratimes.com/rssfeed/2429064.xml` | Marathi | state | verified (200+XML) | national/politics slug; city slugs exist |
| Maharashtra | Pudhari | `https://www.pudhari.news/feed` | Marathi | state | verified (200+XML) | - |
| Maharashtra | Saamana | `https://www.saamana.com/feed` | Marathi | state | verified (200+XML) | - |
| Maharashtra | Dainik Bhaskar (Maharashtra) | `https://www.bhaskar.com/rss-v1--category-2318.xml` | Marathi | state | verified (200+XML) | - |

## Central India

| State/UT | Outlet | Feed URL | Language | Level | Verified | Notes |
|---|---|---|---|---|---|---|
| Chhattisgarh | The Hindu (Chhattisgarh) | `https://www.thehindu.com/news/national/chhattisgarh/feeder/default.rss` | English | state | verified (200+XML) | - |
| Chhattisgarh | Amar Ujala (Chhattisgarh) | `https://www.amarujala.com/rss/chhattisgarh.xml` | Hindi | state | verified (200+XML) | - |
| Chhattisgarh | Live Hindustan (Chhattisgarh) | `https://api.livehindustan.com/feeds/rss/chhattisgarh/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Chhattisgarh | Dainik Bhaskar (Chhattisgarh) | `https://www.bhaskar.com/rss-v1--category-1741.xml` | Hindi | state | verified (200+XML) | - |
| Chhattisgarh | Hari Bhoomi | `https://haribhoomi.com/feed` | Hindi | state | verified (200+XML) | - |
| Madhya Pradesh | The Hindu (Madhya Pradesh) | `https://www.thehindu.com/news/national/madhya-pradesh/feeder/default.rss` | English | state | verified (200+XML) | - |
| Madhya Pradesh | Amar Ujala (Madhya Pradesh) | `https://www.amarujala.com/rss/madhya-pradesh.xml` | Hindi | state | verified (200+XML) | - |
| Madhya Pradesh | Live Hindustan (Madhya Pradesh) | `https://api.livehindustan.com/feeds/rss/madhya-pradesh/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Madhya Pradesh | Dainik Bhaskar (Madhya Pradesh) | `https://www.bhaskar.com/rss-v1--category-1739.xml` | Hindi | state | verified (200+XML) | - |

## North India

| State/UT | Outlet | Feed URL | Language | Level | Verified | Notes |
|---|---|---|---|---|---|---|
| Haryana | The Hindu (Haryana) | `https://www.thehindu.com/news/national/haryana/feeder/default.rss` | English | state | verified (200+XML) | - |
| Haryana | Amar Ujala (Haryana) | `https://www.amarujala.com/rss/haryana.xml` | Hindi | state | verified (200+XML) | - |
| Haryana | Live Hindustan (Haryana) | `https://api.livehindustan.com/feeds/rss/haryana/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Haryana | Dainik Bhaskar (Haryana) | `https://www.bhaskar.com/rss-v1--category-1742.xml` | Hindi | state | verified (200+XML) | - |
| Himachal Pradesh | The Hindu (Himachal Pradesh) | `https://www.thehindu.com/news/national/himachal-pradesh/feeder/default.rss` | English | state | verified (200+XML) | - |
| Himachal Pradesh | Amar Ujala (Himachal Pradesh) | `https://www.amarujala.com/rss/himachal-pradesh.xml` | Hindi | state | verified (200+XML) | - |
| Himachal Pradesh | Live Hindustan (Himachal Pradesh) | `https://api.livehindustan.com/feeds/rss/himachal-pradesh/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Himachal Pradesh | Himachal Dastak | `https://himachaldastak.com/feed` | Hindi | state | verified (200+XML) | - |
| Punjab | The Hindu (Punjab) | `https://www.thehindu.com/news/national/punjab/feeder/default.rss` | English | state | verified (200+XML) | - |
| Punjab | News18 Punjabi (Punjab) | `https://punjab.news18.com/commonfeeds/v1/pan/rss/punjab.xml` | Punjabi | state | verified (200+XML) | only verified Punjabi feed |
| Punjab | Amar Ujala (Punjab) | `https://www.amarujala.com/rss/punjab.xml` | Hindi | state | verified (200+XML) | - |
| Punjab | Live Hindustan (Punjab) | `https://api.livehindustan.com/feeds/rss/punjab/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Punjab | Dainik Bhaskar (Punjab) | `https://www.bhaskar.com/rss-v1--category-1743.xml` | Hindi | state | verified (200+XML) | - |
| Rajasthan | The Hindu (Rajasthan) | `https://www.thehindu.com/news/national/rajasthan/feeder/default.rss` | English | state | verified (200+XML) | - |
| Rajasthan | Amar Ujala (Rajasthan) | `https://www.amarujala.com/rss/rajasthan.xml` | Hindi | state | verified (200+XML) | - |
| Rajasthan | Live Hindustan (Rajasthan) | `https://api.livehindustan.com/feeds/rss/rajasthan/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Rajasthan | Dainik Bhaskar (Rajasthan) | `https://www.bhaskar.com/rss-v1--category-1740.xml` | Hindi | state | verified (200+XML) | - |
| Rajasthan | Patrika (State News) | `https://cms.patrika.com/googlefeed/blog/category/state-news` | Hindi | state | verified (200+XML) | - |
| Uttar Pradesh | The Hindu (Uttar Pradesh) | `https://www.thehindu.com/news/national/uttar-pradesh/feeder/default.rss` | English | state | verified (200+XML) | - |
| Uttar Pradesh | Amar Ujala (Uttar Pradesh) | `https://www.amarujala.com/rss/uttar-pradesh.xml` | Hindi | state | verified (200+XML) | - |
| Uttar Pradesh | Dainik Bhaskar (Uttar Pradesh) | `https://www.bhaskar.com/rss-v1--category-2052.xml` | Hindi | state | verified (200+XML) | - |
| Uttar Pradesh | Dainik Jagran | `https://tools.jagran.com/rss/jagranhindi/jagranhindinews.xml` | Hindi | state | verified (200+XML) | headline-only feed (1 item at check) |
| Uttar Pradesh | Live Hindustan (Uttar Pradesh) | `https://api.livehindustan.com/feeds/rss/uttar-pradesh/rssfeed.xml` | Hindi | state | verified (200+XML) | **0 items at check — prefer Amar Ujala/Bhaskar for UP** |
| Uttarakhand | The Hindu (Uttarakhand) | `https://www.thehindu.com/news/national/uttarakhand/feeder/default.rss` | English | state | verified (200+XML) | - |
| Uttarakhand | Amar Ujala (Uttarakhand) | `https://www.amarujala.com/rss/uttarakhand.xml` | Hindi | state | verified (200+XML) | - |
| Uttarakhand | Live Hindustan (Uttarakhand) | `https://api.livehindustan.com/feeds/rss/uttarakhand/rssfeed.xml` | Hindi | state | verified (200+XML) | - |

## East India

| State/UT | Outlet | Feed URL | Language | Level | Verified | Notes |
|---|---|---|---|---|---|---|
| Bihar | The Hindu (Bihar) | `https://www.thehindu.com/news/national/bihar/feeder/default.rss` | English | state | verified (200+XML) | - |
| Bihar | Live Hindustan (Bihar) | `https://api.livehindustan.com/feeds/rss/bihar/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Bihar | Amar Ujala (Bihar) | `https://www.amarujala.com/rss/bihar.xml` | Hindi | state | verified (200+XML) | - |
| Bihar | Dainik Bhaskar (Bihar) | `https://www.bhaskar.com/rss-v1--category-3679.xml` | Hindi | state | verified (200+XML) | - |
| Bihar | Prabhat Khabar | `https://www.prabhatkhabar.com/feed/` | Hindi | state | verified (200+XML) | - |
| Jharkhand | The Hindu (Jharkhand) | `https://www.thehindu.com/news/national/jharkhand/feeder/default.rss` | English | state | verified (200+XML) | - |
| Jharkhand | Live Hindustan (Jharkhand) | `https://api.livehindustan.com/feeds/rss/jharkhand/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Jharkhand | Amar Ujala (Jharkhand) | `https://www.amarujala.com/rss/jharkhand.xml` | Hindi | state | verified (200+XML) | - |
| Jharkhand | Dainik Bhaskar (Jharkhand) | `https://www.bhaskar.com/rss-v1--category-3682.xml` | Hindi | state | verified (200+XML) | - |
| Jharkhand | Prabhat Khabar | `https://www.prabhatkhabar.com/feed/` | Hindi | state | verified (200+XML) | - |
| Odisha | The Hindu (Odisha) | `https://www.thehindu.com/news/national/odisha/feeder/default.rss` | English | state | verified (200+XML) | - |
| Odisha | News18 Odia (Odisha) | `https://odia.news18.com/commonfeeds/v1/odi/rss/odisha.xml` | Odia | state | verified (200+XML) | - |
| Odisha | Odisha Bhaskar | `https://odishabhaskar.com/feed/` | Odia | state | verified (200+XML) | - |
| Odisha | OdishaTV | `https://odishatv.in/feed` | Odia | state | verified (200+XML) | - |
| Odisha | Dharitri | `https://www.dharitri.com/rss` | Odia | state | verified (200+XML) | - |
| Odisha | Kalinga TV | `https://kalingatv.com/feed/` | Odia/English | state | verified (200+XML) | - |
| West Bengal | The Hindu (West Bengal) | `https://www.thehindu.com/news/national/west-bengal/feeder/default.rss` | English | state | verified (200+XML) | - |
| West Bengal | News18 Bengali (West Bengal) | `https://bengali.news18.com/commonfeeds/v1/ben/rss/west-bengal.xml` | Bengali | state | verified (200+XML) | - |
| West Bengal | Sangbad Pratidin | `https://www.sangbadpratidin.in/feed/` | Bengali | state | verified (200+XML) | - |
| West Bengal | Amar Ujala (West Bengal) | `https://www.amarujala.com/rss/west-bengal.xml` | Hindi | state | verified (200+XML) | - |

## Northeast India

| State/UT | Outlet | Feed URL | Language | Level | Verified | Notes |
|---|---|---|---|---|---|---|
| Arunachal Pradesh | The Hindu (Arunachal Pradesh) | `https://www.thehindu.com/news/national/arunachal-pradesh/feeder/default.rss` | English | state | verified (200+XML) | - |
| Arunachal Pradesh | Arunachal24 | `https://arunachal24.in/feed/` | English | state | verified (200+XML) | - |
| Arunachal Pradesh | The Arunachal Times | `https://arunachaltimes.in/index.php/feed/` | English | state | verified (200+XML) | legacy WordPress path; `/feed/` is dead |
| Assam | The Hindu (Assam) | `https://www.thehindu.com/news/national/assam/feeder/default.rss` | English | state | verified (200+XML) | - |
| Assam | Asomiya Pratidin | `https://www.asomiyapratidin.in/rss` | Assamese | state | verified (200+XML) | - |
| Assam | The Assam Tribune | `https://assamtribune.com/feed` | English | state | verified (200+XML) | `/rss` is dead; `/feed` works |
| Assam | Time8 | `https://www.time8.in/feed` | English | state | verified (200+XML) | - |
| Assam | The Sentinel | `https://www.sentinelassam.com/feed` | English | state | verified (200+XML) | valid but only 1 item at check |
| Assam | Northeast Now | `https://nenow.in/feed/` | English | regional (NE) | verified (200+XML) | covers all NE states |
| Manipur | The Hindu (Manipur) | `https://www.thehindu.com/news/national/manipur/feeder/default.rss` | English | state | verified (200+XML) | - |
| Manipur | Imphal Free Press | `https://ifp.co.in/feed` | English | state | verified (200+XML) | sparse (1 item at check) |
| Manipur | Imphal Times | `https://www.imphaltimes.com/feed` | English | state | verified (200+XML) | - |
| Meghalaya | The Hindu (Meghalaya) | `https://www.thehindu.com/news/national/meghalaya/feeder/default.rss` | English | state | verified (200+XML) | - |
| Meghalaya | The Shillong Times | `https://theshillongtimes.com/feed/` | English | state | verified (200+XML) | - |
| Meghalaya | Meghalaya Monitor | `https://meghalayamonitor.com/feed` | English | state | verified (200+XML) | - |
| Mizoram | The Hindu (Mizoram) | `https://www.thehindu.com/news/national/mizoram/feeder/default.rss` | English | state | verified (200+XML) | - |
| Mizoram | Zoram Observer | `https://zoramobserver.com/feed` | Mizo/English | state | verified (200+XML) | - |
| Mizoram | Amar Ujala (Aizawl) | `https://www.amarujala.com/rss/aizwal.xml` | Hindi | city | verified (200+XML) | sparse (1 item at check) |
| Nagaland | The Hindu (Nagaland) | `https://www.thehindu.com/news/national/nagaland/feeder/default.rss` | English | state | verified (200+XML) | - |
| Nagaland | The Morung Express | `https://morungexpress.com/feed` | English | state | verified (200+XML) | - |
| Nagaland | Nagaland Post | `https://www.nagalandpost.com/rss` | English | state | verified (200+XML) | - |
| Sikkim | The Hindu (Sikkim) | `https://www.thehindu.com/news/national/sikkim/feeder/default.rss` | English | state | verified (200+XML) | - |
| Sikkim | The Sikkim Chronicle | `https://thesikkimchronicle.com/feed` | English | state | verified (200+XML) | - |
| Sikkim | Voice of Sikkim | `https://voiceofsikkim.com/feed` | English | state | verified (200+XML) | sparse (6 items at check) |
| Sikkim | Amar Ujala (Gangtok) | `https://www.amarujala.com/rss/gangtok.xml` | Hindi | city | verified (200+XML) | - |
| Tripura | The Hindu (Tripura) | `https://www.thehindu.com/news/national/tripura/feeder/default.rss` | English | state | verified (200+XML) | - |
| Tripura | Tripura Chronicle | `https://tripurachronicle.in/feed` | English | state | verified (200+XML) | - |
| Tripura | Live Hindustan (Tripura) | `https://api.livehindustan.com/feeds/rss/tripura/rssfeed.xml` | Hindi | state | verified (200+XML) | - |
| Tripura | Northeast Now | `https://nenow.in/feed/` | English | regional (NE) | verified (200+XML) | - |

## Union Territories

| State/UT | Outlet | Feed URL | Language | Level | Verified | Notes |
|---|---|---|---|---|---|---|
| Andaman and Nicobar Islands | The Hindu (Andaman and Nicobar) | `https://www.thehindu.com/news/national/andaman-and-nicobar-islands/feeder/default.rss` | English | UT | verified (200+XML) | - |
| Andaman and Nicobar Islands | Andaman Sheekha | `https://www.andamansheekha.com/feed/` | English | UT | verified (200+XML) | - |
| Chandigarh | The Hindu (Chandigarh) | `https://www.thehindu.com/news/national/chandigarh/feeder/default.rss` | English | UT | verified (200+XML) | - |
| Chandigarh | Amar Ujala (Chandigarh) | `https://www.amarujala.com/rss/chandigarh.xml` | Hindi | UT | verified (200+XML) | - |
| Chandigarh | Live Hindustan (Chandigarh) | `https://api.livehindustan.com/feeds/rss/chandigarh/rssfeed.xml` | Hindi | UT | verified (200+XML) | - |
| Dadra and Nagar Haveli and Daman and Diu | The Hindu (DNH and DD) | `https://www.thehindu.com/news/national/daman-diu-dadra-and-nagar-haveli/feeder/default.rss` | English | UT | verified (200+XML) | - |
| Dadra and Nagar Haveli and Daman and Diu | Daily Kiran | `https://dailykiran.com/feed` | Hindi/Gujarati | UT | verified (200+XML) | - |
| Dadra and Nagar Haveli and Daman and Diu | RamRajya News | `https://ramrajyanews.com/feed` | Hindi | UT | verified (200+XML) | - |
| Delhi | The Hindu (Delhi) | `https://www.thehindu.com/news/cities/Delhi/feeder/default.rss` | English | UT | verified (200+XML) | under `/news/cities/` not `/news/national/` |
| Delhi | Amar Ujala (Delhi) | `https://www.amarujala.com/rss/delhi.xml` | Hindi | UT | verified (200+XML) | - |
| Delhi | Live Hindustan (NCR) | `https://api.livehindustan.com/feeds/rss/ncr/rssfeed.xml` | Hindi | UT | verified (200+XML) | - |
| Delhi | Dainik Bhaskar (New Delhi) | `https://www.bhaskar.com/rss-v1--category-7140.xml` | Hindi | UT | verified (200+XML) | - |
| Jammu and Kashmir | The Hindu (Jammu and Kashmir) | `https://www.thehindu.com/news/national/jammu-and-kashmir/feeder/default.rss` | English | UT | verified (200+XML) | - |
| Jammu and Kashmir | Greater Kashmir | `https://www.greaterkashmir.com/feed` | English | UT | verified (200+XML) | - |
| Jammu and Kashmir | Rising Kashmir | `https://risingkashmir.com/feed` | English | UT | verified (200+XML) | - |
| Jammu and Kashmir | Kashmir Life | `https://kashmirlife.net/feed` | English | UT | verified (200+XML) | - |
| Jammu and Kashmir | Amar Ujala (Jammu and Kashmir) | `https://www.amarujala.com/rss/jammu-and-kashmir.xml` | Hindi | UT | verified (200+XML) | - |
| Jammu and Kashmir | Live Hindustan (Jammu and Kashmir) | `https://api.livehindustan.com/feeds/rss/jammu-and-kashmir/rssfeed.xml` | Hindi | UT | verified (200+XML) | - |
| Ladakh | The Hindu (Ladakh) | `https://www.thehindu.com/news/national/ladakh/feeder/default.rss` | English | UT | verified (200+XML) | - |
| Ladakh | Indus Dispatch | `https://indusdispatch.in/feed/` | English | UT | verified (200+XML) | - |
| Ladakh | News Now Ladakh | `https://newsnowladakh.in/feed/` | English | UT | verified (200+XML) | - |
| Ladakh | Ladakh News Hub | `https://ladakhnewshubofficial.com/feed/` | English | UT | verified (200+XML) | - |
| Lakshadweep | The Hindu (Lakshadweep) | `https://www.thehindu.com/news/national/lakshadweep/feeder/default.rss` | English | UT | verified (200+XML) | - |
| Lakshadweep | Lakshadweep Times | `https://lakshadweeptimes.com/feed` | English | UT | verified (200+XML) | - |
| Puducherry | The Hindu (Puducherry) | `https://www.thehindu.com/news/cities/puducherry/feeder/default.rss` | English | UT | verified (200+XML) | under `/news/cities/` |
| Puducherry | News18 Tamil (Puducherry) | `https://tamil.news18.com/commonfeeds/v1/tam/rss/puducherry.xml` | Tamil | UT | verified (200+XML) | - |
| Puducherry | News Today | `https://newstodaynet.com/feed` | English | UT | verified (200+XML) | - |

---

# 3. Blocked, dead, and HTML-only outlets (do NOT bundle as RSS)

## Akamai / Cloudflare blocked (403) — confirmed at check time

> **Corrections, re-tested 2026-09-14** (full results in `ARCHITECTURE.md` §4.2):
> - **Moneycontrol is NOT blocked.** `https://www.moneycontrol.com/rss/latestnews.xml` returns **200 `application/xml`** (15 KB, valid RSS) both with *and* without a browser UA. The 403 below was transient or timing-dependent - **treat it as usable**.
> - **Scroll.in has a working FeedBurner mirror:** `http://feeds.feedburner.com/ScrollinArticles.rss` → 200 XML (296 KB), which bypasses the HTML-only verdict.
> - **Financial Express has no usable feed:** `/feed/` and `/rss/` are **410 Gone** (`wp_die`); `/market/feed/` and `/business/feed/` return HTML, not feeds.
> - **ThePrint remains JS-walled:** `theprint.in/feed/` returns 200 but as `text/html`.
> - **Lesson:** third-party mirrors (FeedBurner) often outlive the publisher's own path, and blocked verdicts go stale. Re-test before relying on this table.

| Outlet | Attempted URL | Result | Notes |
|---|---|---|---|
| ~~Moneycontrol~~ | `https://www.moneycontrol.com/rss/latestnews.xml` | ~~403~~ **200 xml** | **Correction: usable.** See note above. |
| NDTV (direct) | `https://www.ndtv.com/rss` | 403 | Akamai. Use the FeedBurner mirror instead. |
| The Telegraph (India) | `https://www.telegraphindia.com/feeds/rss` | 403 | Akamai. |
| Zee News | `https://zeenews.india.com/rss/india-national-news.xml` | 403 | Akamai. |
| Times Now | `https://www.timesnownews.com/rss` | 403 | Akamai. |
| Anandabazar Patrika | `https://www.anandabazar.com/rss` | 403 | Akamai. Use News18 Bengali / Sangbad Pratidin instead. |
| The Tribune (Chandigarh) | `https://www.tribuneindia.com/rss` | 403 | Akamai. Leaves Chandigarh thin. |
| Lokmat (Marathi) | `https://www.lokmat.com/rss/` | 403 | Akamai (tiny XML error body). Use Maharashtra Times / Pudhari / Saamana. |
| Sakshi (Telugu) | `https://www.sakshi.com/rss` | 403 | Blocked. |
| Dinakaran (Tamil) | `https://www.dinakaran.com/rss` | 403 | Blocked. |
| Kerala Kaumudi | `https://keralakaumudi.com/rss` | 403 | Blocked. |
| Daily Excelsior (J&K) | `https://www.dailyexcelsior.com/feed` | 403 | Blocked. |
| Divya Himachal | `https://www.divyahimachal.com/feed` | 403 | Blocked. Use Amar Ujala / Live Hindustan HP. |
| Babushahi (Punjab) | `https://babushahi.com/rss` | 403 | Blocked. |
| Punjab News Express | `https://www.punjabnewsexpress.com/feed` | 403 | Blocked. |
| **ThePrint** | `https://theprint.in/feed/` | 403 | **Cloudflare challenge** (`Just a moment…`). Needs a browser/JS; not usable keyless. |

## Reachable but not a usable feed (HTML-only, 404, or 5xx) — use web-page-to-feed

| Outlet | Attempted URL | Result | Notes |
|---|---|---|---|
| The Wire | `https://thewire.in/rss`, `/feed`, `/wp-json/...` | 200 HTML | No native feed; WordPress REST also returns HTML. HTML-only. |
| Scroll.in | `https://scroll.in/feed` | 302→200 HTML | `/feed` redirects to `/page/1`; no native feed. HTML-only. |
| The Quint | `https://www.thequint.com/rss` | 404 | HTML-only. |
| Newslaundry | `https://www.newslaundry.com/rss` | 404 | HTML-only. |
| IndiaSpend | `https://www.indiaspend.com/feed` | 404 | HTML-only. |
| LiveLaw | `https://www.livelaw.in/feed` | 500 | Server error at check. |
| The Statesman | `https://www.thestatesman.com/feed` | 500 | Server error at check. |
| Deccan Chronicle | `https://www.deccanchronicle.com/rss` | 404 | HTML-only. |
| The Hans India | `https://www.thehansindia.com/rss` | 404 | HTML-only. |
| Madhyamam (Malayalam) | `https://www.madhyamam.com/rss`, `/feed` | 404/500 | No feed. |
| Manorama Online (direct) | `https://www.manoramaonline.com/rss` | 404 | Use OnManorama feeds (`onmanorama.com/*.feeds.onmrss.xml`). |
| Udayavani (Kannada) | `https://www.udayavani.com/feed`, `/rss` | 200 HTML | No feed. |
| Sandesh (Gujarati) | `https://sandesh.com/rss` | 200 HTML | No feed. |
| Gujarat Samachar | `https://www.gujaratasamachar.com/rss` | 404 | No feed. |
| Bartaman (Bengali) | `https://bartamanpatrika.com/rss` | 404 | No feed. |
| Ei Samay (Bengali) | `https://eisamay.com/rss` | 200 HTML | No feed. |
| Sambad (Odia) | `https://sambad.in/feed/` | 404 | No feed (other Odia outlets cover). |
| Dinamalar (Tamil) | `https://www.dinamalar.com/rss` | 200 HTML | No feed. |
| Daily Thanthi (Tamil) | `https://www.dailythanthi.com/rss` | 404 | No feed. |
| Sun News (Tamil) | `https://www.sunnews.in/rss` | conn. failed | No feed. |
| PTC News (Punjabi) | `https://www.ptcnews.tv/rss` | 200 HTML | No feed. |
| Punjab Kesari / Ajit / Jagbani | `/rss`, `/feed` | 404/403/conn. failed | No feed. |
| Loksatta / Lokmat Times (Marathi) | `/feed`, `/rss` | 200 HTML | No feed (JS app). |
| Gomantak Times (Goa) | `https://www.gomantaktimes.com/feed` | 200 XML | **Feed present but 0 items — empty, skip.** |
| ABP Majha / Zee24Kalak | `/rss` | 200 HTML / 404 | No feed. |
| First India (Rajasthan) | `https://firstindia.co.in/rss` | 404 | No feed. |
| Vanglaini (Mizo) | `https://www.vanglaini.org/feed` | 404 | No feed. |
| Tripura Times | `https://tripuratimes.com/feed` | 404 | No feed; Tripura Chronicle covers. |
| Reach Ladakh | `https://reachladakh.com/feed` | 200 HTML | No feed. |
| The Goan | `https://www.thegoan.net/rss` | 200 HTML | No feed. |
| Sakal (Marathi) | `https://www.sakal.com/rss/` | conn. failed | No feed. |

---

# 4. Coverage gaps — where the catalog is weakest

**States/UTs with only one real local source (or none):**
- **Lakshadweep** — no established daily with a feed; only The Hindu + Lakshadweep Times.
- **Dadra & Nagar Haveli and Daman & Diu** — no established daily; The Hindu + Daily Kiran + RamRajya News.
- **Puducherry** — no dedicated Puducherry daily feed; The Hindu + News18 Tamil Puducherry + News Today.
- **Andaman & Nicobar** — The Hindu + Andaman Sheekha only.
- **Chandigarh** — The Tribune blocked; only The Hindu + Hindi-belt feeds.

**Local-language gaps (no usable feed found; English/local-region feeds must substitute):**
- **Meghalaya** — Khasi/Garo: none (English only).
- **Nagaland** — Nagamese/Ao/Tenyidie: none (English only).
- **Mizoram** — Mizo: only Zoram Observer (bilingual); Vanglaini has no feed.
- **Manipur** — Meitei: none (English only).
- **Sikkim** — Nepali: none (English only).
- **Tripura** — Bengali/Kokborok: none (Hindi via Live Hindustan, English via Tripura Chronicle/Northeast Now).
- **Arunachal Pradesh** — no local-language feed (English only).
- **Ladakh** — Ladakhi/Balti/Tibetan: none (English only).
- **Punjab** — Punjabi is thin: News18 Punjabi is the only verified Punjabi feed.
- **DNH & DD / Lakshadweep** — regional-language feeds absent.

**Feeds to treat with caution (valid XML but sparse/empty):**
`The News Minute` (0 items), `Live Hindustan Uttar Pradesh` (0 items), `The Sentinel` (1), `Imphal Free Press` (1), `Amar Ujala Aizawl` (1), `Vikatan` (1), `Puthiya Thalaimurai` (1), `Dainik Jagran` (1), `Prajavani` (3), `Voice of Sikkim` (6).

**Special case — O Heraldo (Herald Goa):** returns 200 with 10 items but the XML is malformed (duplicate `xmlns:media` attribute). Strict parsers reject it; tolerant RSS readers accept it. If your parser is strict, drop it — Goa still has The Hindu and The Navhind Times.

**Google News editions:** Google News has **no Kannada, Odia, or Assamese edition**. For Karnataka, Odisha and Assam this catalog is the only local-language path, and it does provide it (Prajavani/Kannada Prabha/Asianet Kannada/News18 Kannada; Odisha Bhaskar/OdishaTV/Dharitri/Kalinga TV/News18 Odia; Asomiya Pratidin).

---

# 5. Runtime discovery patterns (for districts not in this catalog)

Districts (784) are intentionally not catalogued. These publisher URL patterns let the app discover district/city feeds at runtime by slug:

- **News18 (largest district network):** `https://<locale>.news18.com/commonfeeds/v1/<code>/rss/<slug>.xml`
  - locale/code: `hindi/hin`, `tamil/tam`, `telugu/tel`, `kannada/kan`, `malayalam/mal`, `bengali/ben`, `marathi/mar`, `gujarati/guj`, `assam/asm`, `odia/odi`, `punjab/pan`, `urdu/urd`.
  - state feeds: `tel/andhra-pradesh.xml`, `tel/telangana.xml`, `tam/tamil-nadu.xml`, `mal/kerala.xml`, `ben/west-bengal.xml`, `mar/maharashtra.xml`, `guj/gujarat.xml`, `asm/assam.xml`, `odi/odisha.xml`, `pan/punjab.xml`; Kannada main = `kan/latest.xml`.
  - district examples: `tel/andhra-pradesh/<district>.xml`, `guj/<city>.xml`, `tam/<district>.xml`, `kan/mysuru.xml`, `kan/mangaluru.xml`, `odi/bhubaneswar.xml`, `pan/amritsar.xml`, `urd/jammu-kashmir.xml`. Enumerate slugs from each locale's `/rss` page.
- **Amar Ujala:** `https://www.amarujala.com/rss/<slug>.xml` — ~300 city/state slugs (e.g. `agra`, `lucknow`, `patna`, `dehradun`, `jaipur`, `bhopal`, `raipur`, `ranchi`, `chandigarh`, `srinagar`). Enumerate from `https://www.amarujala.com/rss`.
- **Live Hindustan (Hindustan Hindi):** `https://api.livehindustan.com/feeds/rss/<state>/rssfeed.xml` and district `…/<state>/<district>/rssfeed.xml`. Enumerate from `https://www.livehindustan.com/rss`.
- **Dainik Bhaskar:** `https://www.bhaskar.com/rss-v1--category-<id>.xml`; known state IDs: MP 1739, Rajasthan 1740, Chhattisgarh 1741, Haryana 1742, Punjab 1743, UP 2052, Gujarat 2314, Maharashtra 2318, Bihar 3679, Jharkhand 3682, New Delhi 7140. Divya Bhaskar (Gujarati) uses the same scheme (`1035` = My Gujarat). Enumerate from `https://www.bhaskar.com/rss`.
- **The Hindu:** `https://www.thehindu.com/news/national/<state-slug>/feeder/default.rss` and `https://www.thehindu.com/news/cities/<City>/feeder/default.rss` (city slug is case-sensitive for some, e.g. `Delhi`, `puducherry`).
- **Firstpost:** `https://www.firstpost.com/commonfeeds/v1/mfp/rss/<section>.xml` (enumerate from `https://www.firstpost.com/rss/`).
- **Patrika:** `https://cms.patrika.com/googlefeed/blog/category/<slug>` (e.g. `state-news`, `national-news`).
- **OnManorama:** `https://www.onmanorama.com/<section>.feeds.onmrss.xml` (e.g. `kerala`, `news/india`).

---

# 6. Sitemap-index sources (outlets with no RSS)

Some of the best regional coverage has **no RSS** but exposes an XML **sitemap** that works as a poll-able index (article URLs + `lastmod`). Verified 2026-09-13.

| Outlet | Index | Entries | Language(s) | Notes |
|---|---|---|---|---|
| **ETV Bharat** | `https://www.etvbharat.com/<lang>/<state>/latest_news_sitemap.xml` | — | **Kannada (`/kn/karnataka/`), Odia (`/or/odisha/`), Assamese (`/as/assam/`)**, Bengali, Tamil, Telugu, Malayalam, Marathi, Gujarati, Punjabi, Urdu, Hindi | Sitemap index at `https://www.etvbharat.com/sitemap.xml`. Article pages are **server-rendered** (verified on an Odia article: 279 KB HTML, real text + `og:` tags) → jsoup-parseable. **No RSS.** Fills the Google News Kannada/Odia/Assamese gap. robots.txt allows general crawlers but blocks AI bots by UA. |
| **The Hindu** (sitemaps) | `/sitemap/update/all.xml` (477), `/sitemap/googlenews/all/all.xml` (477), `/sitemap/update/section.xml` (63) | 477 | English | robots-allowed; RSS also exists → sitemap is a *sweep/backfill* index, not a replacement. |
| **Hindustan Times** | `/sitemap/news.xml` (1,139), `/sitemap/index.xml` (**sitemapindex, 764**), `/sitemap/section.xml` (231), `/telugu/sitemap/news.xml` (700), `/bangla/sitemap/news.xml` (700) | — | English, **Telugu, Bangla** | `index.xml` is a full-archive index. RSS also at `/feeds/rss/<section>/rssfeed.xml` (100 items; `pubDate` not found — verify). |

**Ei Samay (Bengali):** `https://www.eisamay.com/feed` — verified 200, valid RSS, but only **3 items** and mislabels `<language>en-us</language>`. Thin; treat as supplemental.

**Directory seed:** `https://www.indiapress.org/gen/browse_state.php` and `gen/browse_city.php/<City>` — an HTML state/city newspaper directory to enumerate outlets for feed discovery.

**Adapter implication:** one adapter type — **sitemap-index source** — covers no-RSS outlets (ETV Bharat) and augments RSS outlets (The Hindu, HT) as a completeness sweep.

---

## Appendix — verification method

```
curl -sS -L --max-time 25 -A "<Chrome UA>" -o body.xml -w "%{http_code}|%{content_type}" <URL>
xmllint --noout body.xml        # well-formedness
grep -cE '<item>|<entry>' body.xml   # item count snapshot
```
- Run **2026-09-13 (IST)** from a residential Indian IP.
- All URLs in sections 1–2 were fetched live; statuses are point-in-time and publisher blocks can change.
- HTTP 200 + well-formed XML is necessary but not sufficient for long-term stability; add periodic re-validation in the app.
- No X/Twitter, Facebook or LinkedIn sources are included. No paywalled, JS-only or keyed APIs are included.
