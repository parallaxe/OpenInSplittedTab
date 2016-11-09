package org.para.plugin;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Created by hendrikvonprince on 09/11/16.
 */
public class OpenInSplittedTabBaseAction extends AnAction {

    private GotoDeclarationAction gotoDeclarationAction; // used as alternative, when no PsiElement is found as target
    private Boolean closePreviousTab;

    public OpenInSplittedTabBaseAction(Boolean closePreviousTab) {
        this.closePreviousTab = closePreviousTab;
        this.gotoDeclarationAction = new GotoDeclarationAction();
    }

    public void actionPerformed(AnActionEvent e) {
        final PsiElement target = getTarget(e);

        // if we got a valid symbol we will open it in a splitted tab, else we call the GotoDeclarationAction
        if (target != null) {
            final Project project = PlatformDataKeys.PROJECT.getData(e.getDataContext());
            final FileEditorManagerEx fileEditorManager = FileEditorManagerEx.getInstanceEx(project);
            final EditorWindow nextWindowPane = receiveNextWindowPane(project, fileEditorManager, e.getDataContext());

            fileEditorManager.setCurrentWindow(nextWindowPane);
            // We want to replace the current active tab inside the splitter instead of creating a new tab.
            // So, we save which file is currently open, open the new file (in a new tab) and then close the
            // previous tab. To do this, we save which file is currently open.
            final VirtualFile fileToClose = fileEditorManager.getCurrentFile();
            // use the openFileImpl2-method instead of the openFile-method, as the openFile-method would open a new
            // window when the assigned shortcut for this action includes the shift-key
            nextWindowPane.getManager().openFileImpl2(nextWindowPane, target.getContainingFile().getVirtualFile(), true);
            // Of course, we don't want to close the tab if the new target is inside the same file as before.
            if(this.closePreviousTab && fileToClose != null && !fileToClose.equals(target.getContainingFile().getVirtualFile())) {
//                Logger.getInstance("OpenInSplittedTab").error(fileToClose.getPresentableUrl() + target.getContainingFile().getVirtualFile().getPresentableUrl());
                fileEditorManager.getCurrentWindow().closeFile(fileToClose);
            }

            scrollToTarget(target, nextWindowPane);
        } else {
            this.gotoDeclarationAction.actionPerformed(e);
        }
    }

    private void scrollToTarget(PsiElement target, EditorWindow nextWindowPane) {
        // defer the scrolling of the new tab, otherwise the scrolling may not work properly
        Timer delayingScrollToCaret = new Timer(10, actionEvent -> {
            if (!nextWindowPane.isShowing()) {
                scrollToTarget(target, nextWindowPane);
            } else {
                nextWindowPane.setAsCurrentWindow(true);
                nextWindowPane.getManager().getSelectedTextEditor().getCaretModel().moveToOffset(target.getTextOffset());
                nextWindowPane.getManager().getSelectedTextEditor().getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }
        }
        );
        delayingScrollToCaret.setRepeats(false);
        delayingScrollToCaret.start();
    }

    @Override
    public void update(AnActionEvent e) {
        PsiElement target = getTarget(e);
        if (target != null) {
            e.getPresentation().setEnabled(true);
        } else {
            // we found no target for our own action, but maybe we can execute the GotoDeclaration-action
            this.gotoDeclarationAction.update(e);
        }
    }

    /**
     * @param fileEditorManager
     * @param project
     * @param dataContext
     * @return If there already are splitted tabs, it will return the next one. If not, it creates a vertically splitted tab
     */
    private EditorWindow receiveNextWindowPane(Project project,
                                               FileEditorManagerEx fileEditorManager,
                                               DataContext dataContext) {
        final EditorWindow activeWindowPane = EditorWindow.DATA_KEY.getData(dataContext);
        if (activeWindowPane == null) return null; // Action invoked when no files are open; do nothing

        EditorWindow nextWindowPane = fileEditorManager.getNextWindow(activeWindowPane);

        if (nextWindowPane == activeWindowPane) {
            FileEditorManagerEx fileManagerEx = (FileEditorManagerEx) FileEditorManagerEx.getInstance(project);
            fileManagerEx.createSplitter(SwingConstants.VERTICAL, fileManagerEx.getCurrentWindow());
            nextWindowPane = fileEditorManager.getNextWindow(activeWindowPane);
        }
        return nextWindowPane;
    }

    /**
     * @return The first <code>PsiElement</code> that is found by the GotoDeclarationAction for the currently selected <code>PsiElement</code>
     */
    @Nullable
    private PsiElement getTarget(AnActionEvent e) {
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        Editor editor = e.getData(PlatformDataKeys.EDITOR);

        if (psiFile == null || editor == null) {
            e.getPresentation().setEnabled(false);
            return null;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement elementAt = psiFile.findElementAt(offset);

        if (elementAt == null) {
            e.getPresentation().setEnabled(false);
            return null;
        }

        PsiElement[] allTargetElements = GotoDeclarationAction.findAllTargetElements(elementAt.getProject(), editor, offset);
        return allTargetElements.length > 0 ? allTargetElements[0] : null;
    }
}
