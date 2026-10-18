#!/usr/bin/env python3
"""Assemble RSS multi-class sets from labelled pools. Quiet, deterministic.

Inputs: rss_pool.labelled.jsonl, ext_pool.labelled.jsonl, label15k.labelled.jsonl,
        labour_labels.json, existing rss_test.jsonl (frozen).
Mapping: strike/bandh kinds -> incident (labour-action is a rule, not a bucket);
         none -> other; games/paper/personal/promo/expense dropped.
Test: frozen 113 + audited other rows (see OTHER_TEST_IDS). Everything else,
story-deduped against test, goes to train.
Usage: python eval/build_rss.py
"""
import json
import os
import random
import re

HERE = os.path.dirname(os.path.abspath(__file__))
CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education", "other"]
DROP = {"games", "paper", "personal", "promo", "expense"}

# Audited other rows (human read 2026-10-16): politics/crime/culture, none fit the 8.
OTHER_TEST_IDS = {
    "https://bengali.abplive.com/district/west-bengal-minister-of-state-health-department-sumona-sarkar-bjp-records-song-for-durga-puja-2026-1193681",
    "https://www.thehindu.com/entertainment/movies/pooja-meri-jaan-trailer-of-huma-qureshi-and-mrunal-thakurs-film-unveiled/article71508254.ece",
    "https://www.thehindu.com/food/features/meet-the-people-keeping-india-culinary-memories-alive/article71467082.ece",
    "https://news.google.com/rss/articles/CBMimgJBVV95cUxOa3ZIcm5GaUlHM1RRX1hiTG9vRGYteG5OelB5YmhCZlY3S1NaV3Z5ZmNfODNxT0MweTAtLVY5bkMwdzc3S1BGQzVJbXd3SV9NTHZjODVlMWc4Y2Zmd2ZldzBmV2h1cElFRFo0ZlFzemIyUjh4eFJVSklOb0N2dElndGZ2T2dRcFZpXzBER2tRZzN2YWpfdndsdWNPNjh4TXJlQXlud3A1MkhTYndncWpnSWZYX0Y2Q29kRTc5eGs4ZWtuYzc4dWRnZFZRUDNKR19vaGI4M3YzdksybGkwMm5sUElabW1yX195R3lrUzZ3VU9tNzhpUnVkbTZRS0NzamxUckphRldmOGc2ckVoT0l0S0t2RVRBNVBWZHfSAaACQVVfeXFMTk1TM3I1ZlRZbU5PbE51YTVQOEMyRExCNHltTzVKWUE2YWpEX0dBQ0l5aGNjNDFjVXk4QmhJQVRIbi1NeGFhSDNnRlphbzZFeXE3NXNMNkhidTFUUVRzQ294SmJtd2Vtd0gzSmg2TnFhcVpTV0ZmS0hFSjlRRS1zc3R1WEVuWjRQR0tHUlFKVmFZTS1WdS1TRFZpYjdlN2l3bk5heldKVWNNN3VqRmJvUjVVTXZZTzE5Uno0ZHg1UEZ0dFpRZ1gzWkxsRERFMXkyelZkdFJqR2N4Ti1CMlQtZHd1cTlSVnRFd1plX1NzU3hNamIyNDFGNDNveVBUTGRzT1dHTkp4aGFQVnliNUozNjkxT2llcXBoa1p5Z3VZbzZs?oc=5",
    "https://news.google.com/rss/articles/CBMi7wFBVV95cUxOQ0NyS3hmUWpXdndCWXNOUmlNNndUT0dIU3E2Y3MybDdnNU4zYlZrWDBURjBjd1VMNmpiNW90VGpQT0VydXdzU3FlVkVWc0ZJcm95VE1jOGRtbmx3V0NYU25KZ3BBRDlOaUtpT2twdmdjOWxleEhaMmRYWTlSMS1YOHVPUU1sdnVRU1lYZGVNOXZLQ3I3NDdCYTBZaDlwT2lhdkU2UUFHYUNxb29ydHBxVGFTQnMycEMwaTRjTFR5U3BkNExmaTU1NnFSTlUwSEdPOVdsdDlXa3o5bkl5VHFaTFF3MU1hQTNJdWhMbFpIRdIB9gFBVV95cUxNOUFrTWtCLThKRm52aEM1QUV4WnF5T2JZaENkXy0xWUtsV1gxM05oTXBkSlUzUDFLc1pmRDNFamp2WndqSUxQSDMwTFBGZlQ1MTg1TUEycEVrVl95OHBMeUlMODlkYlJpb0dwZDMteWVnSDlKN3pHMmtPY3o3dkdydExHajE0dWpKbnNrWG1HbTltNjd5RTI1Qi1ZSzBBRGg4dDZJNWlDb1NjbVA1UXlsZEpyNXpQUWZucG5PVlNrNEFpV19ZUWdUQm1FbkRuclRQeGRaZ1prWEtseTdEX1JSRnR1VjJsenV1Q3A0ZTlGa2ZCbzNUVlE?oc=5",
    "https://www.thehindu.com/news/national/odisha/graham-staines-murder-case-rejected-convict-dara-singhs-remission-plea-odisha-tells-supreme-court/article71475672.ece",
    "https://news.google.com/rss/articles/CBMiwgFBVV95cUxQT1doVl9KaVJoek1NM2tDNTZyQmQwTGZndFFVWHdnMmdFYmlOMEJNWFpDamxldE1NeWNKMjhIZ2FySnYyNHEwcUdIaWJLWjN2N2hSUkIxRFV5TkkycFJEOE85ay03MVpZbUV5Qk85OG1KX2tjcmIxNkd2UVpNNjE1S3RyZllCSlNFMHVobG4xNkdrYlNTUlBoVDVWVlk2RE0yMmFreW8xZFZEV0RNTjRBdFd0LUoxSy1XWklhLU92SVFzUdIBxwFBVV95cUxQUjl3WkFJd2k2UkEtWjVWblpvNXo5ekwweVgxdXdzeUxOTUlfMHRuX3JqWjFMNWR2MFNVOGR3RWJpWFVFRW50Y1pQWEtyT1JfN1h0ajh0M3ZkLWV5bVBVbDNucGFWWHFhbkdVeHJfcllxTEJiVDRqaEtxaVdlcURQY0Z2ZGJOMXFpYVF3QmZ5SEdqQXdLSUx6cWxmbXkxRU5SY3VqX0NtbUFqRmg0TmdvQ3liRTdXMXl6RW15M1NpN3RzTnhwempJ?oc=5",
    "https://www.thehindu.com/opinion/op-ed/why-congress-must-not-smear-cjp/article71580953.ece",
    "https://www.hindustantimes.com/india-news/after-nandigram-tmcs-rejinagar-candidate-rabiul-chowdhury-withdraws-from-bypoll-in-fresh-setback-for-mamata-banerjee-101789799469042.html",
    "https://www.hindustantimes.com/india-news/air-pollution-linked-to-higher-risk-of-suicidal-thoughts-study-reveals-what-exposure-to-pm2-5-pm10-does-101789554611628.html",
    "https://www.hindustantimes.com/india-news/mayawati-bsp-jaipur-polls-bjp-udiapur-kota-bhilwara-rajasthan-municipal-body-101789451877866.html",
    "https://news.google.com/rss/articles/CBMi0gFBVV95cUxQNG44ZnVob25VV2tkMzk2aU5FbVJiZTFQdWtiVF94Zm9Pc1dKSEx5cDduRGlNanR4Z3ZZdUZ2VWIxWEk2Yk5iU2lJb0tpU1FDbmNtZE9WR1ZtVTBRcDA1cUdkVnFKblV6S0hDdTBwaG91NWJBNzVqYmw1TlExcWJkb0ZiNllMN3BoZC1YblV4NTVLOFN2T2VUOC1qc2hUMld4ZUVsMkVFN2hzZ2VxU0FWRm9LSTlVb0ZfcmVkU1h3X3pXWllXQmtxcEVCUWFaVTVOUVHSAdoBQVVfeXFMUEdSd1EwMFpFS1pZUjhxMHZIZEFWVmItMDNtYmlpNGpKRGFXMVFnaHFNSE5meUZSRElfZUFXWFViZmRCeExyUzNkVERKbGtUTjRTVVh0ZHg4YklRbWZMMlpabmxrbmVGemROOGN2M0xQRU9jTWktdTU5cnNVQU5kbFhoQnkyeE84Z2czZndpSnhrSTFsZkphQWtOMlZGRlgzS1Rwc005bVkySi02SDRGb1RoNzZ1NkNBVFFLaG94Z28xR0gyU1picmRCNVpHbVZJdTc2aXZVajdqdmc?oc=5",
    "https://news.google.com/rss/articles/CBMihAJBVV95cUxQWDhHRGZWbS1nclJuVk83NUczTkthdnRhTXViRDFnVTBqTzU2YXloMVBicFM0WEJhczNGaWFpN3pFNHM0XzJNQ1ptTURzTXFBOWc4RWxLeTRHTlVJMDFKZGRDdmVHUmY2dUQ1M1o2Y0VjQnRUZ0xxLVR6elk4QkwwSDVQZlZNLUZKSWsySFpqRl9uX2ZaTkxITEpndWdBUzZOd3lNTkNSSUxyRkhjeXl1dm15ZjFxU3dzUEl0SDB4OTU5MXpPRGMxSkJfa0Fnc1g1TnpveDJYZ1dnRlFyYURtM2lvRHBDRm45UXFlVjhqbGtyZndmTGtBVExHdGp1eVNMNVhPc9IBigJBVV95cUxQU05mWnpmMVhtaXdSd0kyZkl6MEpOZUZ3SnJUdVlVSEtQbUY2b28yckVHOG5QRWhUT2FaUEZsQ2xlMHd5UnFvYlp2VkN6eHNYTnp0WkI1NnVFbDFyNW00WEYzZ1VpMUdFMDBDUjFlbzg2QTBPb2dJQ25sWGp3NVV3WnFPei0zVnVEZ2pTNFJ6dmpIXzFacnJzRGVJU2lVT2pxbFJOREhjNzV5TF9ialJLc0NXMXZyVmhNbTd4dE4wSHVxcE92VkJQeDBlN3FoWjgxaTVqODZfanJhS0dQMjdCa0ZSaTVwVWFyWGZndHpueVpnNU9Qci1Zakk2aUpaN2tGUTByeVZxbHMtQQ?oc=5",
    "https://www.hindustantimes.com/india-news/from-pandas-to-precious-stones-the-hidden-language-of-gift-diplomacy-101790325199530.html",
    "https://bengali.abplive.com/videos/district/we-didn-t-know-that-joraphul-meant-mamata-and-abhishek-saya-madan-mitra-watch-video-1192876",
    "https://news.google.com/rss/articles/CBMi0AJBVV95cUxQSGQ4Nkc2OHlWcnFHVkgxOG8ya2FYMThwUHZrSUFRbG4xLWpnY3pEU0pleDJ4U1RkNzU5Y25kYW5QOFBFVWZsSG1PRXh3VG1xdTdoOWZzdERDRURQTWs5MlJ5Tl9hemlDb1lfUVN4LTg1ZVk4NmU3V0ZkWHMtaHhMVHlIMGI3WE1kMmVXa21oa3lVUUxsNVVoX0NFaWtoY0pFc1l2NENrQlpENGNsdjdzdHRSb2E4Y0ZHU1hMdmVia3NmMndGWlRKT3ZBSUs4eUh0b2dPNm5oSUNXMzdKTzBBMU0tcEl6dU1fZGZKeWJ5WTJNZ01CUVcwWHgxZnEzNy1QUzluU3pfNnZUN2Z3WmtWRTVXOFcxRktxZEo0RTZrVDY3TTl1LU40eC1YdmdTU1FJSl9tR3VYNHMzMVVFbVZHZUhLM2hyaG05eHUwTGZWQkvSAYYCQVVfeXFMUGpaTFgwUVZhTDhXLVJIVjZKdElFQmN1YVhiSHFMMXZid0U1N1hRdnQ3N0pOdmtYdHFNaGRndzRzd3RfOEU2VUJtdWdEQXc0NjJuZ1JyaVpsWldMVzh0anBuWkNudTZLZS1zeWRiXzhQVUdxZUFQOTNISzExVjJIREhjNGJsMXRuWlBhOWhLRU5Ja0RnczZEd1dMZEFfVC1NWkYzaU1sZnFndm9kMlBOXzFTSHdNT2g0S0hhMmJ0eXNPVEQwSHFJdDlNYl9PQ0NmTU43bUlDY0JJclREUEJwbVdWdFprSXN5dHpSSHNCbUI4R0ZLU0MzZUpNNmdOclZFMW5PMjB1UQ?oc=5",
    "https://www.thehindu.com/books/how-cities-in-fiction-help-readers-locate-real-world-places/article71468223.ece",
    "https://www.hindustantimes.com/india-news/16yearold-girl-held-for-murdering-grandmother-with-friends-help-in-karnatakas-mandya-101791871710895.html",
    "https://indianexpress.com/article/lifestyle/r-madhavan-17-5-crore-mumbai-home-balcony-organic-garden-10873372/",
    "https://news.google.com/rss/articles/CBMi4AFBVV95cUxPNjZ6bjljV29BRFZudXUza1NCTk40d20zZU9UX2xNNmdkY3hkZkRzcmJ3RHRQZ3U2b0JYR2F2b0VlRTBkZjJncWV6ckxDYkJ4M2Zva2owLU1XbmZwQkFQVjBidTFfM09DRXdlYk1NY3JMaEUtZDVzWkE1QXd3Y0wtNjhVSXRCM2ZXaXY3VGl3MU1ULTd0aW5nS3JhX2FGek9WdTAxOTRKLUxoV3dnNGQ4VXRiM3ZQNFAxLUJBdExCUmhWSWJmbjhoV0Q2cnVlX3F5Yko4MmNHOGhHa1A5VGtnZ9IB5gFBVV95cUxQWWdMeGJPOVg1TDRiQ2ZzRWNIdjRiMkt1VmdWRnpUUExqRmhqYUI5ZVU0YUpmTE9nTzVTT2RlTzZtUlRlM0FmUUYyVjlWbHBoaU0yX1pUWGIxcVlOTnJoZm5KYkJIOXd6b2ZKaWhXNTVPSEF2RHhlYVBvS1NuYlpNYkZsY0QzaWkxN2FpcEtNb0ZaekIxYVNQT3VJaXpEa2duR2hnSDJfSjBNQl9OMl9mMjUxV08xQ0dxVllJaHdTWFFNaFpWTmlnVnl2WjIwZDZHXzZ6YkNfMFRIU1FybHVXQ0NHX1haQQ?oc=5",
    "https://www.thehindu.com/sci-tech/health/polypharmacy-why-taking-multiple-medicines-needs-close-monitoring/article71471567.ece",
    "https://news.google.com/rss/articles/CBMi3gFBVV95cUxPVjVxR3NKdm9Cay1XTmJmQ2k1NEVCemhuNFZHY1NNc05YNWVfZmhUR1ZVcTQtQjh3ZmlSY1NBUTFzSVlFc0Z1c1FKXy1sem96VWV4dGY5OVA3bkxWWk9XdU43NWRmVjZvSXdoSmFfVUc3Rko4U0xEczdhN2ViLS1Vb1FoU1FSemtnZ1IwMzhWcHVXMFJhU1pJak9fZE8teTdfOXNiVU9aSjNscHR0UnF0cTJpd3A0c3gxcEhxem13R1REQ0FZeklHUzNQNXBBanJfRkNMZGJYQjFyRzNOaFHSAeMBQVVfeXFMUDNTR05Ga0ctRW1wUWNpNVIyNjdwUFI4ZkFZdW5aemZKcW1VVDk0T01CQ3AyakFlenlTMW9QR0YwV0ZTLWQ4RkNMenhZS0tpSVptLU9jbXlMUzM5dk1lOUZ5d2J0N3FOaGc1LVNZcUVXWEE5MW0xVUlvMU1kdllPWFJ1QmF0dGlKZ0dHZjA0WWpTYWVYYS1INGlRaVN6Nlc5amJnb0ZDck5jNlhPLVR6aVBKbG1rZEJmY0dMaGdYYnJMTHlnXzR2em9ITVZ5em9CbDkyTXE3WlBURlpQTHdncF90VVk?oc=5",
    "https://bengali.abplive.com/district/cafe-opportunity-cafe-success-story-of-disabled-persons-sign-mou-with-calcutta-university-kolkata-offbeat-special-child-efforts-1193691",
    "https://news.google.com/rss/articles/CBMi5AFBVV95cUxPaG5nMlVncmhWQnF2ZkRXQW8yeVRZUS01MUhxbXdBWWJ1a1JMRHhTTUh2SldEOUNXMlFBQW9EYnVremdQTXhBQm1lWS0zMHM3OUFOV3p4VFRtNEd3VDlWZDRSdU1oM0h5T016dU9CODJOZEd0UkE3ZGowaTFtenVXQTVJMTVBR2F1Q0toSzZ5ajNLT20tZ0xpbjFLS0NPdGZnQUlRSlhQQ1pCOW1ITnYtUnBYbk5zd2FWVW9OaHBmdERKM0xRZ011bkNldHNJY3FDZ1lQSFlzeUQ3dF9OMkwyaTZhRlTSAeoBQVVfeXFMT01GTEdrc19HaXBmd0ZuUmpZeFNLMDhVVVJqNXdqalZfU0NGT0RJTmlWN2xHZEsyekNMZGZLRHdfa0xTdXl4Q1Q4Qy1PVkZRT0VNbkZ1bkhKZklGdk1rS2RuODVpTGNHSGdEQndqLVg4ZkI3NFEyMk03Ri1zN0dDYnBQTG9PV0dTWTJTclN0MWk5Z3A4ZU5kTXZHcXZoQnlyaWM2b3lQNUNjak9xM0RaaVltQ1NHZTJYdU9WaU1LRVg5SXkwcW9HUTRheVBjSlBERnJ5TUVOSzR0QmgyY0ZvMkZYZ0FCWVFYTHd3?oc=5",
    "https://news.google.com/rss/articles/CBMi9wFBVV95cUxNR1lyMWsxT04wbDVzdURXZmI0NWtRNlZIR0tMZnVBRXRMY1F5bW02c3RwUUZhYzFmN19vdlJyQ1hIV2haN2NFOUladld1TTdjMnVpZnZ4S0tkcm1WNU9IZVIxVndsSW5QOFYxZnhBRHc0ckF1ZUw5MGV6MlNOZFFpVW5CbWpZLWY4TnprTWdVVDRBbnV3V1VJeXNWX19CQy1mcThlMjVXTG1GM3AzVDM4ck1iTGFYLVNYS0JfYzlTNVNaLW8wclRhVlZIUTlZaVNFdGNjU0gwblhZbXNMUkJQVENiWVV3NXlfY0R3M0NUbE9uVVROX1NJ?oc=5",
    "https://news.google.com/rss/articles/CBMi1AFBVV95cUxPel96RXJWMWstSHJrWTF4bXFoejRIZS03NEpZbVpNOUVUZ2N1QlBkR2Uwbkw1a2tjOVZBTVJOQUkwMGJKcl95OURkcFBHRHRtMDNqNHZLSmlfRUFPeTg2Q1ZoaUV1RmZldVdybzBxRVRfSFdOM29FNUJ4U2xmZHBzTkhkV2NwakNBaGZ2X29DZUR3czM3a2hEVEJmQUJQZ3BlX0NXOThNUjVMdWFMNmJCQW9oYXR0VXlkYmM2cHVoZFk4TUxBZFhZSDdOUzlETlozMVBwRdIB2gFBVV95cUxPQWRMN0NDTTZDTWtzb2pRV1V0aDJ6RVd5Tmg2LVIwdmtkd1N3QlVCUmtMYkJib0Q5cW1EWnRlTXo4b0tkY0NRWlZkckptNzJWSjdkS2EzVjZLVmhZZUdKcFdST2pHUkphQnNQbnp3ck1Pd3BneldqRmRzX1Q3WWluSUM4aWhLaHNIUUpfbG5obklMVUs0d0QzdlhVakNIZWx6b0tWQnlQdDg3cnp3RkRBeTlMVGZFbFFwcGVNQ2JQdDZjQ0Zvb1AwWTY3Rk1CWTJXcU1SZWg0Vl8yUQ?oc=5",
    "https://www.thehindu.com/videos/watch-sonia-gandhi-voter-roll-row-delhi-court-orders-fresh-probe-review/article71492250.ece",
    "https://news.google.com/rss/articles/CBMixAFBVV95cUxPYzdNWHkwQ0pSRjBsTmRrZU40cjkwTnRVRmg4YUFNZHI4TGpwWGhvYkdpNzhLVlF3TmxiZ0ZxbDJ3TXFuRGdYMzFhWjJHX2ZhdS1HLXB1eEZsRTdYSHpXUUFCTE5MZm4tWFBCd09SOHFoLTFKYVFhdzM2RDNDek10bVNBNUxLSjNKYnJGWG9QUktWZk5IYlQ0VUJTRkRaQ1NZbS1FOFdSMERyaVpaVUdnRWFnOVBJUkxvbmlORnptYkJ3RlhW0gHKAUFVX3lxTE9EeXJJanRaMEViZmtnLW95cHdGZl94TmRQVFBmMHdIaklIb3lNcUpxYXlRV05rdFBLQkJJWG1NLUpSZ0xJNFgtMm5tQmFpemRoM1ltT2RjWFRHNU9uelE1ZU9vTG9EZlBhWi1qVU1zRG5VekFpMmJCU2x3WWpMWG44SWFZNVVvc2dvV3VXdG5rbVplT0hTbnNETWRodHk0ZWtFZHd3X2RiZVVGa25wcG1xSmdJcjRPVU5RSDNqbVpjenE5N0x1bURhSFE?oc=5",
    "https://news.google.com/rss/articles/CBMi9gFBVV95cUxOeFRBMUVlRVRCTnprUjhpTWc2R0lXNUlMeFNWNThNZm54YTg1dzk5UUtLR2E1RkMwaGx1RWFhLVZhN3pRVm80VWZKUkM5WGpLOUp4SXU0ZmpKVzVCUlFja3NzWWpDaXJDWXJtU2tDTXUxY0xHVDJGdElhRTZtNjlJQTg4LUR6djRESmxXVmNUSEd1bmg2cVR3OXhmelpwYW9RUGlmbXlmNW10Q1NkSnIxbnM5NlFsZHZhNjVYSWp5MGJ3b3VDcDd3bUlCUHRPZjdhTDE0aC10aGFic2lCb0VKWkp0VUhhQ1d0WHZ5SnpTaE9CQm5fZmc?oc=5",
    "https://www.thehindu.com/news/national/tamil-nadu/tamil-nadu-bypoll-results-tvk-madurantakam-dharapuram-constituencies/article71563406.ece",
}


def story_key(t):
    t = t.lower()
    t = re.sub(r"\s*[|\-–—]\s*(the hindu|times of india|live law|bar and bench|"
               r"the news minute|ndtv\.com|cricinfo|deccan herald|telegraph india|"
               r"the statesman|the new indian express).*$", "", t)
    return re.sub(r"\s+", " ", t).strip()


def load_pool(path):
    with open(os.path.join(HERE, path), encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


def main():
    lab = json.load(open(os.path.join(HERE, "labour_labels.json")))["labels"]
    db_titles = {}
    import sqlite3
    db = sqlite3.connect("/tmp/pradar/personalos.db")
    for eid, title in db.execute("SELECT id, title FROM events"):
        db_titles[str(eid)] = title.strip().lower()
    title2kind = {}
    for eid, kind in lab.items():
        if eid in db_titles:
            title2kind[db_titles[eid]] = kind

    frozen = load_pool("rss_test.jsonl")
    frozen_ids = {r["id"] for r in frozen}
    frozen_keys = {story_key(r["subject"]) for r in frozen}

    pool = (load_pool("rss_pool.labelled.jsonl") + load_pool("ext_pool.labelled.jsonl")
            + load_pool("label15k.labelled.jsonl"))
    train, test_other, dropped = [], [], {k: 0 for k in DROP}
    seen = set()
    for r in pool:
        if not r.get("label") or r["id"] in frozen_ids:
            continue
        kind = title2kind.get(r["subject"].strip().lower())
        cls = "incident" if kind in ("strike", "bandh") else r["label"]
        if cls == "none":
            cls = "other"
        if cls in DROP:
            dropped[cls] += 1
            continue
        assert cls in CLASSES, cls
        row = {"id": r["id"], "subject": r["subject"], "body": r["body"],
               "source": r.get("source", "?"), "label": cls, "ts": r.get("ts", 0)}
        if r["id"] in OTHER_TEST_IDS:
            row["split"] = "test"
            test_other.append(row)
        else:
            k = story_key(r["subject"])
            if k in frozen_keys or k in seen:
                continue
            seen.add(k)
            row["split"] = "train"
            train.append(row)

    random.seed(21)
    random.shuffle(train)
    test = frozen + test_other
    with open(os.path.join(HERE, "rss_train.jsonl"), "w", encoding="utf-8") as f:
        f.write("\n".join(json.dumps(r, ensure_ascii=False) for r in train))
    with open(os.path.join(HERE, "rss_test.jsonl"), "w", encoding="utf-8") as f:
        f.write("\n".join(json.dumps(r, ensure_ascii=False) for r in test))
    from collections import Counter
    print(f"train {len(train)} {dict(sorted(Counter(r['label'] for r in train).items()))}")
    print(f"test {len(test)} (frozen {len(frozen)} + other {len(test_other)}) "
          f"{dict(sorted(Counter(r['label'] for r in test).items()))}")
    print(f"dropped {dropped}")


if __name__ == "__main__":
    main()
