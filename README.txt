NEPSE Pulse — Google Play privacy pages (draft)

Files:
- privacy-policy.html
- delete-account.html

How to publish on GitHub Pages from your phone:
1. Open https://github.com/anishacharya95/Nepse-pulse
2. Open the repository's main/root file list (the same level as index.html).
3. Add file -> Create new file.
4. Create privacy-policy.html and paste the complete contents from privacy-policy.html in this package. Commit the change.
5. Repeat for delete-account.html.
6. After GitHub Pages updates, test:
   https://anishacharya95.github.io/Nepse-pulse/privacy-policy.html
   https://anishacharya95.github.io/Nepse-pulse/delete-account.html

Important limitations:
- These are draft pages, not a completed account-deletion feature.
- The project uses Supabase authentication and a cloud watchlists table. Deletion must securely remove the Supabase Auth user (which requires a trusted server-side/admin operation or manual processing in Supabase) and confirm associated user data is removed.
- Do not put a Supabase service-role key in public HTML or JavaScript.
- Add an in-app route/link to the account-deletion request page or an in-app request method before Play submission, and actually monitor/process deletion emails.
- Confirm the production app's full data collection and ad SDK behavior before submitting the Data safety form. The policy may need updating if more services or data types are used.
