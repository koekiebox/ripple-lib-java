package com.ripple.android;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.ripple.client.Account;
import com.ripple.client.Client;
import com.ripple.client.Response;
import com.ripple.client.blobvault.BlobVault;
import com.ripple.client.subscriptions.AccountRoot;
import com.ripple.client.transactions.Transaction;
import com.ripple.client.transactions.TransactionManager;
import com.ripple.client.transactions.TransactionMessage.TransactionResult;
import com.ripple.client.transport.impl.JavaWebSocketTransportImpl;
import com.ripple.core.types.AccountID;
import com.ripple.core.types.Amount;
import org.json.JSONException;
import org.json.JSONObject;

class Logger {
    private static final String LOG_TAG = "PayOneDrop";

    public static void LOG(String s, Object... args) {
        Log.d(LOG_TAG, String.format(s, args));
    }
}

class JSON {
    public static JSONObject parseJSON(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static String prettyJSON(JSONObject jsonObject) {
        try {
            return jsonObject.toString(4);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}

class AndroidClient extends Client {
    Handler handler;

    public AndroidClient(Handler handler) {
        super(new JavaWebSocketTransportImpl());
        this.handler = handler;
    }

    @Override
    public void sendMessage(JSONObject msg) {
        log("sending: ", JSON.prettyJSON(msg));
        super.sendMessage(msg);
    }

    /**
     * This is to ensure we run everything on the ui thread (as per activity lifecycle
     * handlers onCreate and OnClickListener handlers)
     */
    @Override
    public void onMessage(final JSONObject msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                log("received: ", JSON.prettyJSON(msg));
                AndroidClient.super.onMessage(msg);
            }
        });
    }
}

public class PayOneDrop extends Activity {
    AndroidClient client;
    Account account;
    Handler handler;
    TextView status;
    EditText username;
    EditText password;

    Button submit;
    DownloadBlobTask blobDownloadTask;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        setupClient();
        setupViews();
    }

    private void setupClient() {
        handler = new Handler();
        client = new AndroidClient(handler);
        client.connect("wss://ct.ripple.com");
        account = null;
    }

    private void setupViews() {
        status   = (TextView) findViewById(R.id.status);
        username = (EditText) findViewById(R.id.username);
        password = (EditText) findViewById(R.id.password);
        submit   = (Button)   findViewById(R.id.submit);

        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (account != null) {
                    if (!account.root.primed()) {
                        setStatus("Awaiting account_info");
                    }
                    else {
                        payNiqOneDrop(account);
                    }
                }
                else {
                    if (!loginFieldsValid()) {
                      setStatus("Must enter username and password");
                    }else if (blobDownloadTask == null) {
                        blobDownloadTask = new DownloadBlobTask();
                        blobDownloadTask.execute(username.getText().toString(),
                                password.getText().toString());
                        setStatus("Retrieving blob!");
                    } else {
                        setStatus("Waiting for blob to be retrieved!");
                    }
                }
            }
        });
    }

    private boolean accountIsUnfunded() {
        return account.root.Balance.isZero();
    }

    private void handleUnfundedAccount() {
        setStatus("Account unfunded");
        showLogin();
        account = null;
    }

    private boolean loginFieldsValid() {
        return username.length() > 0 && password.length() > 0;
    }

    private void setSubmitToPay() {
        submit.setVisibility(View.VISIBLE);
        submit.setText("Pay niq one drop!");
    }

    private void showLogin() {
        username.setVisibility(View.VISIBLE);
        password.setVisibility(View.VISIBLE);
        submit.setVisibility(View.VISIBLE);
        submit.setText("Retrieve blob");
    }

    private void hideLogin() {
        username.setVisibility(View.GONE);
        password.setVisibility(View.GONE);
        submit.setVisibility(View.GONE);
    }

    private void payNiqOneDrop(Account account){
        makePayment(account, "rP1coskQzayaQ9geMdJgAV5f3tNZcHghzH", "1");
    }

    private void makePayment(final Account account, Object destination, String amt) {
        TransactionManager tm = account.transactionManager();
        Transaction tx = tm.payment();

        tx.put(AccountID.Destination, destination);
        tx.put(Amount.Amount, amt);

        tx.once(Transaction.OnSubmitSuccess.class, new Transaction.OnSubmitSuccess() {
            @Override
            public void called(Response response) {
                setStatus("Transaction submitted " + awaitingTransactionsParenthetical(account));
            }
        });
        tx.once(Transaction.OnSubmitError.class, new Transaction.OnSubmitError() {
            @Override
            public void called(Response response) {
                setStatus("Transaction submission failed" + awaitingTransactionsParenthetical(account));
            }
        });
        tx.once(Transaction.OnTransactionValidated.class, new Transaction.OnTransactionValidated() {
            @Override
            public void called(TransactionResult result) {
                setStatus("Transaction finalized " + awaitingTransactionsParenthetical(account));
            }
        });
        tm.queue(tx);
    }
    private String awaitingTransactionsParenthetical(Account account) {
        return String.format("(awaiting %d)", account.transactionManager().awaiting());
    }

    private void setStatus(String str) {
        status.setText(str);
    }

    private class DownloadBlobTask extends AsyncTask<String, String, JSONObject> {
        @Override
        protected void onPostExecute(JSONObject blob) {
            blobDownloadTask = null;
            if (blob == null) {
                setStatus("Failed to retrieve blob!");
                showLogin();
                return;
            }
            try {
                setStatus("Retrieved blob!");
                account = client.accountFromSeed(blob.getString("master_seed"));
                account.root.once(AccountRoot.OnUpdate.class, new AccountRoot.OnUpdate() {
                    @Override
                    public void called(AccountRoot accountRoot) {
                        if (accountIsUnfunded()) {
                            handleUnfundedAccount();
                        }
                    }
                });
                setSubmitToPay();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected void onPreExecute() {
            hideLogin();
        }

        @Override
        protected JSONObject doInBackground(String... credentials) {
            try {
                BlobVault blobVault = new BlobVault("https://blobvault.payward.com/");
                String username = credentials[0];
                String password = credentials[1];
                return blobVault.getBlob(username, password);
            } catch (Exception e) {
                return null;
            }
        }
    }
}