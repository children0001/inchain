package org.inchain.wallet;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.URL;

import javax.swing.ImageIcon;

import org.inchain.kit.InchainInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
 
/**
 * 印链桌面客户端（钱包）
 * @author ln
 *
 */
public class Main extends Application implements ActionListener {
	
	private static final Logger log = LoggerFactory.getLogger(Main.class);
	

	private InchainInstance instance;
	
	//如果系统支持托盘， 在第一次点击关闭按钮时，最小化到托盘，弹出提示
	private boolean hideTip;
		
	private TrayIcon trayIcon;
	private Stage stage;

 
	/**
	 * 程序入口
	 * @param args
	 */
	public static void main(String[] args) {
		launch(args);
	}
 
	/**
	 * 启动方法
	 */
	@Override
	public void start(final Stage stage) throws Exception {
		this.stage = stage;

//		stage.initStyle(StageStyle.UTILITY);
		
		//设置程序标题
		stage.setTitle(Constant.APP_TITLE);
		//设置程序图标
		stage.getIcons().add(new Image(getClass().getResourceAsStream(Constant.APP_ICON)));
		
		//初始化系统托盘
        initSystemTray();

		//初始化容器
		initContainer();
        
        //初始化监听器
        initListener();
        
        //启动核心
        startAppKit();
        
		//显示界面
		show();
	}

	private void startAppKit() {
		instance = InchainInstance.newInstance();
		instance.startup(2);
	}

	/**
	 * 停止
	 */
	@Override
	public void stop() throws Exception {
		super.stop();
		instance.shutdown();
		System.exit(0);
	}
	
	/*
	 * 初始化界面容器
	 */
	private void initContainer() throws IOException {
		
        //初始化菜单
        initMenu();
        
        URL location = getClass().getResource("/resources/template/main.fxml");
        FXMLLoader loader = new FXMLLoader(location);
        

        Pane mainUI = loader.load();
 
        StackPane uiStack = new StackPane(mainUI);
		Scene scene = new Scene(uiStack);
        scene.getStylesheets().add(getClass().getResource("/resources/css/wallet.css").toString());
        stage.setScene(scene);
		

	}

	/*
	 * 初始化菜单
	 */
	private void initMenu() {
		
	}
	
	/*
     * 初始化监听器
     */
	private void initListener() {
		//当点击"X"关闭窗口按钮时，如果系统支持托盘，则最小化到托盘，否则关闭
		stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
            @Override
            public void handle(WindowEvent event) {
                if(SystemTray.isSupported()) {
            		//隐藏，可双击托盘恢复
            		hide();
            		if(!hideTip) {
            			TrayIcon[] trayIcons = SystemTray.getSystemTray().getTrayIcons();
            			if(trayIcons != null && trayIcons.length > 0) {
            				trayIcons[0].displayMessage("温馨提示", "印链客户端已最小化到系统托盘，双击可再次显示", MessageType.INFO);
            			}
            			hideTip = true;
            		}
            	} else {
            		//退出程序
            		exit();
            	}
            }
        });
	}

	/*
     * 初始化系统托盘
     */
    private void initSystemTray() {
    	//判断系统是否支持托盘功能
        if(SystemTray.isSupported()) {
        	//获得托盘图标图片路径
            URL resource = this.getClass().getResource(Constant.APP_ICON);
            trayIcon = new TrayIcon(new ImageIcon(resource).getImage(), Constant.TRAY_DESC, createMenu());
            //设置双击动作标识
            trayIcon.setActionCommand("db_click_tray");
            //托盘双击监听
            trayIcon.addActionListener(this);
            //图标自动适应
            trayIcon.setImageAutoSize(true);
            
            SystemTray sysTray = SystemTray.getSystemTray();
            try {
                sysTray.add(trayIcon);
            } catch (AWTException e) {
            	log.error(e.getMessage(), e);
            }
        }
    }
    
    /*
     * 创建托盘菜单
     */
    private PopupMenu createMenu() {
    	PopupMenu popupMenu = new PopupMenu(); //创建弹出菜单对象
    	
    	//创建弹出菜单中的显示主窗体项.
        MenuItem itemShow = new MenuItem("显示印链");
        itemShow.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
            	show();
            }
        });
        
        popupMenu.add(itemShow);
        
        //创建弹出菜单中的退出项
        MenuItem itemExit = new MenuItem("退出系统");    
        popupMenu.add(itemExit);
        
        //给退出系统添加事件监听
        itemExit.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                exit();
            }            
        });
        
        
        return popupMenu;
    }
 
	/**
     * 事件监听处理
     */
	@Override
	public void actionPerformed(ActionEvent e) {
		
		String command = e.getActionCommand();
        
        if("db_click_tray".equals(command)) {
        	//双击托盘，显示窗体
        	//多次使用显示和隐藏设置false
			if (stage.isShowing()) {
				hide();
			} else {
				show();
			}					
        }
        
	}
	
	/**
	 * 显示窗口
	 */
	public void show() {
    	Platform.setImplicitExit(false);
		Platform.runLater(new Runnable() {
			@Override
			public void run() {
				stage.show();
			}
		});
	}
	
	/**
	 * 隐藏窗口
	 */
	public void hide() {
		Platform.runLater(new Runnable() {
			@Override
			public void run() {
				stage.hide();
			}
		});
	}
	
	/**
	 * 程序退出
	 */
	public void exit() {
		SystemTray.getSystemTray().remove(trayIcon);
		Platform.exit();
	}
	
	
 
}