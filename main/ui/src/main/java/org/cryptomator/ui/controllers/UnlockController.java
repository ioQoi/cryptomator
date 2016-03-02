/*******************************************************************************
 * Copyright (c) 2014, 2016 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/
package org.cryptomator.ui.controllers;

import java.net.URL;
import java.util.Comparator;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;

import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.SystemUtils;
import org.cryptomator.crypto.engine.InvalidPassphraseException;
import org.cryptomator.crypto.engine.UnsupportedVaultFormatException;
import org.cryptomator.frontend.CommandFailedException;
import org.cryptomator.frontend.FrontendCreationFailedException;
import org.cryptomator.frontend.FrontendFactory;
import org.cryptomator.frontend.webdav.mount.WindowsDriveLetters;
import org.cryptomator.ui.controls.SecPasswordField;
import org.cryptomator.ui.model.Vault;
import org.fxmisc.easybind.EasyBind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Lazy;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

public class UnlockController extends AbstractFXMLViewController {

	private static final Logger LOG = LoggerFactory.getLogger(UnlockController.class);

	private final Application app;
	private final ExecutorService exec;
	private final Lazy<FrontendFactory> frontendFactory;
	private final WindowsDriveLetters driveLetters;
	private final ChangeListener<Character> driveLetterChangeListener = this::winDriveLetterDidChange;
	final ObjectProperty<Vault> vault = new SimpleObjectProperty<>();

	@Inject
	public UnlockController(Application app, ExecutorService exec, Lazy<FrontendFactory> frontendFactory, WindowsDriveLetters driveLetters) {
		this.app = app;
		this.exec = exec;
		this.frontendFactory = frontendFactory;
		this.driveLetters = driveLetters;
	}

	@FXML
	private SecPasswordField passwordField;

	@FXML
	private TextField mountName;

	@FXML
	private Label winDriveLetterLabel;

	@FXML
	private ChoiceBox<Character> winDriveLetter;

	@FXML
	private Button advancedOptionsButton;

	@FXML
	private Button unlockButton;

	@FXML
	private ProgressIndicator progressIndicator;

	@FXML
	private Text messageText;

	@FXML
	private Hyperlink downloadsPageLink;

	@FXML
	private GridPane advancedOptions;

	@Override
	public void initialize() {
		advancedOptions.managedProperty().bind(advancedOptions.visibleProperty());
		mountName.addEventFilter(KeyEvent.KEY_TYPED, this::filterAlphanumericKeyEvents);
		mountName.textProperty().addListener(this::mountNameDidChange);
		if (SystemUtils.IS_OS_WINDOWS) {
			winDriveLetter.setConverter(new WinDriveLetterLabelConverter());
		} else {
			winDriveLetterLabel.setVisible(false);
			winDriveLetterLabel.setManaged(false);
			winDriveLetter.setVisible(false);
			winDriveLetter.setManaged(false);
		}
		unlockButton.disableProperty().bind(passwordField.textProperty().isEmpty());

		EasyBind.subscribe(vault, this::vaultChanged);
	}

	@Override
	protected URL getFxmlResourceUrl() {
		return getClass().getResource("/fxml/unlock.fxml");
	}

	@Override
	protected ResourceBundle getFxmlResourceBundle() {
		return ResourceBundle.getBundle("localization");
	}

	private void vaultChanged(Vault newVault) {
		if (newVault == null) {
			return;
		}
		passwordField.clear();
		advancedOptions.setVisible(false);
		advancedOptionsButton.setText(resourceBundle.getString("unlock.button.advancedOptions.show"));
		progressIndicator.setVisible(false);
		if (SystemUtils.IS_OS_WINDOWS) {
			winDriveLetter.valueProperty().removeListener(driveLetterChangeListener);
			winDriveLetter.getItems().clear();
			winDriveLetter.getItems().add(null);
			winDriveLetter.getItems().addAll(driveLetters.getAvailableDriveLetters());
			winDriveLetter.getItems().sort(new WinDriveLetterComparator());
			winDriveLetter.valueProperty().addListener(driveLetterChangeListener);
		}
		downloadsPageLink.setVisible(false);
		messageText.setText(null);
		mountName.setText(newVault.getMountName());
		if (SystemUtils.IS_OS_WINDOWS) {
			chooseSelectedDriveLetter();
		}
	}

	// ****************************************
	// Downloads link
	// ****************************************

	@FXML
	public void didClickDownloadsLink(ActionEvent event) {
		app.getHostServices().showDocument("https://cryptomator.org/downloads/");
	}

	// ****************************************
	// Advanced options button
	// ****************************************

	@FXML
	private void didClickAdvancedOptionsButton(ActionEvent event) {
		advancedOptions.setVisible(!advancedOptions.isVisible());
		if (advancedOptions.isVisible()) {
			advancedOptionsButton.setText(resourceBundle.getString("unlock.button.advancedOptions.hide"));
		} else {
			advancedOptionsButton.setText(resourceBundle.getString("unlock.button.advancedOptions.show"));
		}
	}

	private void filterAlphanumericKeyEvents(KeyEvent t) {
		if (t.getCharacter() == null || t.getCharacter().length() == 0) {
			return;
		}
		char c = CharUtils.toChar(t.getCharacter());
		if (!(CharUtils.isAsciiAlphanumeric(c) || c == '_')) {
			t.consume();
		}
	}

	private void mountNameDidChange(ObservableValue<? extends String> property, String oldValue, String newValue) {
		if (vault.get() == null) {
			return;
		}
		// newValue is guaranteed to be a-z0-9_, see #filterAlphanumericKeyEvents
		if (newValue.isEmpty()) {
			mountName.setText(vault.get().getMountName());
		} else {
			vault.get().setMountName(newValue);
		}
	}

	/**
	 * Converts 'C' to "C:" to translate between model and GUI.
	 */
	private class WinDriveLetterLabelConverter extends StringConverter<Character> {

		@Override
		public String toString(Character letter) {
			if (letter == null) {
				return resourceBundle.getString("unlock.choicebox.winDriveLetter.auto");
			} else {
				return Character.toString(letter) + ":";
			}
		}

		@Override
		public Character fromString(String string) {
			if (resourceBundle.getString("unlock.choicebox.winDriveLetter.auto").equals(string)) {
				return null;
			} else {
				return CharUtils.toCharacterObject(string);
			}
		}

	}

	/**
	 * Natural sorting of ASCII letters, but <code>null</code> always on first, as this is "auto-assign".
	 */
	private static class WinDriveLetterComparator implements Comparator<Character> {

		@Override
		public int compare(Character c1, Character c2) {
			if (c1 == null) {
				return -1;
			} else if (c2 == null) {
				return 1;
			} else {
				return c1 - c2;
			}
		}
	}

	private void winDriveLetterDidChange(ObservableValue<? extends Character> property, Character oldValue, Character newValue) {
		if (vault.get() == null) {
			return;
		}
		vault.get().setWinDriveLetter(newValue);
	}

	private void chooseSelectedDriveLetter() {
		assert SystemUtils.IS_OS_WINDOWS;
		// if the vault prefers a drive letter, that is currently occupied, this is our last chance to reset this:
		if (driveLetters.getOccupiedDriveLetters().contains(vault.get().getWinDriveLetter())) {
			vault.get().setWinDriveLetter(null);
		}
		final Character letter = vault.get().getWinDriveLetter();
		if (letter == null) {
			// first option is known to be 'auto-assign' due to #WinDriveLetterComparator.
			this.winDriveLetter.getSelectionModel().selectFirst();
		} else {
			this.winDriveLetter.getSelectionModel().select(letter);
		}
	}

	// ****************************************
	// Unlock button
	// ****************************************

	@FXML
	private void didClickUnlockButton(ActionEvent event) {
		mountName.setDisable(true);
		advancedOptionsButton.setDisable(true);
		progressIndicator.setVisible(true);
		downloadsPageLink.setVisible(false);
		CharSequence password = passwordField.getCharacters();
		exec.submit(() -> this.unlock(password));

	}

	private void unlock(CharSequence password) {
		try {
			vault.get().activateFrontend(frontendFactory.get(), password);
			vault.get().reveal();
		} catch (InvalidPassphraseException e) {
			Platform.runLater(() -> {
				messageText.setText(resourceBundle.getString("unlock.errorMessage.wrongPassword"));
				passwordField.requestFocus();
			});
		} catch (UnsupportedVaultFormatException e) {
			LOG.warn("Unable to unlock vault: " + e.getMessage());
			Platform.runLater(() -> {
				downloadsPageLink.setVisible(true);
				if (e.isVaultOlderThanSoftware()) {
					messageText.setText(resourceBundle.getString("unlock.errorMessage.unsupportedVersion.vaultOlderThanSoftware") + " ");
				} else if (e.isSoftwareOlderThanVault()) {
					messageText.setText(resourceBundle.getString("unlock.errorMessage.unsupportedVersion.softwareOlderThanVault") + " ");
				}
			});
		} catch (FrontendCreationFailedException e) {
			LOG.error("Decryption failed for technical reasons.", e);
			Platform.runLater(() -> {
				messageText.setText(resourceBundle.getString("unlock.errorMessage.decryptionFailed"));
			});
		} catch (CommandFailedException e) {
			LOG.error("Failed to reveal mounted vault", e);
		} finally {
			Platform.runLater(() -> {
				passwordField.swipe();
				mountName.setDisable(false);
				advancedOptionsButton.setDisable(false);
				progressIndicator.setVisible(false);
			});
		}
	}

}
