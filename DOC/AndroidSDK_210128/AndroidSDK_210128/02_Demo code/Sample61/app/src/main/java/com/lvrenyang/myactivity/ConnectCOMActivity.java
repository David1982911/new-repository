package com.lvrenyang.myactivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.lvrenyang.dsview.ComboBox;
import com.csnprintersdk.csnio.csnbase.CSNCOMIO;
import com.csnprintersdk.csnio.csnbase.CSNIOCallBack;
import com.csnprintersdk.csnio.CSNPOS;
import com.lvrenyang.sample6.R;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
public class ConnectCOMActivity extends Activity implements OnClickListener,
		CSNIOCallBack {

	private ComboBox cbxPort, cbxBaud;
	Button btnOpen, btnClose, btnPrint;
	ConnectCOMActivity mActivity;

	ExecutorService es = Executors.newScheduledThreadPool(30);
	CSNPOS mPos = new CSNPOS();
	CSNCOMIO mCom = new CSNCOMIO();
	private static final int nBaudTable[] = { 4800, 9600, 19200, 38400, 57600,
			115200, 230400, 256000, 500000, 750000, 1125000, 1500000 };

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_connectcom);

		mActivity = this;

		btnOpen = (Button) findViewById(R.id.buttonOpen);
		btnClose = (Button) findViewById(R.id.buttonClose);
		btnPrint = (Button) findViewById(R.id.buttonPrint);
		btnOpen.setOnClickListener(this);
		btnClose.setOnClickListener(this);
		btnPrint.setOnClickListener(this);
		btnClose.setEnabled(false);
		btnPrint.setEnabled(false);

		cbxPort = (ComboBox) findViewById(R.id.cbxPort);
		cbxBaud = (ComboBox) findViewById(R.id.cbxBaud);
		for (int baud : nBaudTable) {
			cbxBaud.addString("" + baud);
		}
		cbxBaud.setText("9600");

		mPos.Set(mCom);
		mCom.SetCallBack(this);

		enumSerialPort();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		btnClose.performClick();
	}

	public void onClick(View arg0) {
		// TODO Auto-generated method stub
		switch (arg0.getId()) {

		case R.id.buttonOpen:
			try {
				final int baudrate = Integer.parseInt(cbxBaud.getText());
				final String text = cbxPort.getText();

				Toast.makeText(mActivity, "Connecting...", Toast.LENGTH_SHORT)
						.show();
				btnOpen.setEnabled(false);
				btnClose.setEnabled(false);
				btnPrint.setEnabled(false);
				es.submit(new Runnable() {
					@Override
					public void run() {
						mCom.Open(text, baudrate, 1, 8 ,0,0,0);
					}
				});
			} catch (Exception ex) {
				Toast.makeText(this, ex.toString(), Toast.LENGTH_SHORT).show();
				break;
			}
			break;

		case R.id.buttonClose:
			mCom.Close();
			break;

		case R.id.buttonPrint:
			btnPrint.setEnabled(false);
			es.submit(new TaskPrint(mPos));
			break;

		default:
			break;

		}

	}

	private void enumSerialPort() {
		String[] devicePaths = CSNCOMIO.enumPorts();
		if (devicePaths != null) {
			for (int i = 0; i < devicePaths.length; ++i) {
				String name = devicePaths[i];
				cbxPort.addString(name);
				String text = cbxPort.getText();
				if (text.trim().equals("")) {
					text = name;
					cbxPort.setText(text);
				}
			}
		}
	}

	static int dwWriteIndex = 1;

	public class TaskPrint implements Runnable {
		CSNPOS pos = null;

		public TaskPrint(CSNPOS pos) {
			this.pos = pos;
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub

			final int bPrintResult = Prints.PrintTicket(
					getApplicationContext(), pos, AppStart.nPrintWidth,
					AppStart.bCutter, AppStart.bDrawer, AppStart.bBeeper,
					AppStart.nPrintCount, AppStart.nPrintContent,
					AppStart.nCompressMethod);
			final boolean bIsOpened = pos.GetIO().IsOpened();

			mActivity.runOnUiThread(new Runnable() {
				@Override
				public void run() {
					// TODO Auto-generated method stub
					Toast.makeText(
							mActivity.getApplicationContext(),
							(bPrintResult >= 0) ? getResources().getString(
									R.string.printsuccess) +" "+Prints.ResultCodeToString(bPrintResult) : getResources()
									.getString(R.string.printfailed)
									+ " "
									+ Prints.ResultCodeToString(bPrintResult),
							Toast.LENGTH_SHORT).show();
					mActivity.btnPrint.setEnabled(bIsOpened);
				}
			});

		}

	}

	@Override
	public void OnOpen() {
		// TODO Auto-generated method stub
		this.runOnUiThread(new Runnable() {

			@Override
			public void run() {
				btnOpen.setEnabled(false);
				btnClose.setEnabled(true);
				btnPrint.setEnabled(true);
				Toast.makeText(mActivity, "Connected", Toast.LENGTH_SHORT)
						.show();
			}
		});
	}

	@Override
	public void OnOpenFailed() {
		// TODO Auto-generated method stub
		this.runOnUiThread(new Runnable() {

			@Override
			public void run() {
				btnOpen.setEnabled(true);
				btnClose.setEnabled(false);
				btnPrint.setEnabled(false);
				Toast.makeText(mActivity, "Failed", Toast.LENGTH_SHORT).show();
			}
		});
	}

	@Override
	public void OnClose() {
		// TODO Auto-generated method stub
		this.runOnUiThread(new Runnable() {

			@Override
			public void run() {
				btnOpen.setEnabled(true);
				btnClose.setEnabled(false);
				btnPrint.setEnabled(false);
			}
		});
	}

}