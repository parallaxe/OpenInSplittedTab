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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Created by Hendrik von Prince on 22.02.14.
 */
public class OpenInSplittedTabAction extends AnAction {

    private boolean closeCurrentSelectedFile;
    private GotoDeclarationAction gotoDeclarationAction; // used as alternative, when no PsiElement is found as target

    public OpenInSplittedTabAction() {
        this.gotoDeclarationAction = new GotoDeclarationAction();
    }

    public void actionPerformed(AnActionEvent e) {
        final PsiElement target = getTarget(e);

        // if we got a valid symbol we will open it in a splitted tab, else we call the GotoDeclarationAction
        if (target != null) {
            final EditorWindow nextWindowPane = receiveNextWindowPane(e.getDataContext());
            VirtualFile currentSelectedFile = nextWindowPane.getSelectedFile();
            nextWindowPane.getManager().openFileImpl2(nextWindowPane, target.getContainingFile().getVirtualFile(), true);

            if (this.closeCurrentSelectedFile) {
                nextWindowPane.closeFile(currentSelectedFile);
            }

            // defer the scrolling of the new tab, otherwise the scrolling may not work properly
            Timer delayingScrollToCaret = new Timer(10, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    nextWindowPane.setAsCurrentWindow(true);
                    nextWindowPane.getManager().getSelectedTextEditor().getCaretModel().moveToOffset(target.getTextOffset());
                    nextWindowPane.getManager().getSelectedTextEditor().getScrollingModel().scrollToCaret(ScrollType.CENTER);
                }
            }
            );
            delayingScrollToCaret.setRepeats(false);
            delayingScrollToCaret.start();
        } else {
            this.gotoDeclarationAction.actionPerformed(e);
        }
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
     * @param dataContext
     * @return If there already are splitted tabs, it will return the next one. If not, it creates a vertically splitted tab
     */
    private EditorWindow receiveNextWindowPane(DataContext dataContext) {
        // the following lines are copied and modified from the tab-to-next-splitter-plugin at https://github.com/jacksingleton/tab-to-next-splitter/blob/master/src/com/jacksingleton/tabtonextsplitter/TabToNextSplitter.java
        final EditorWindow activeWindowPane = EditorWindow.DATA_KEY.getData(dataContext);
        final Project project = PlatformDataKeys.PROJECT.getData(dataContext);
        final FileEditorManagerEx fileEditorManager = FileEditorManagerEx.getInstanceEx(project);
        if (activeWindowPane == null) return null; // Action invoked when no files are open; do nothing

        EditorWindow nextWindowPane = fileEditorManager.getNextWindow(activeWindowPane);

        this.closeCurrentSelectedFile = false;

        if (nextWindowPane == activeWindowPane) {
            this.closeCurrentSelectedFile = true;
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
