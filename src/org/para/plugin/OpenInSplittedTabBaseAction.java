package org.para.plugin;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.find.actions.ShowUsagesAction;
import com.intellij.ide.util.DefaultPsiElementCellRenderer;
import com.intellij.lang.LanguageNamesValidation;
import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * Created by hendrikvonprince on 09/11/16.
 */
public class OpenInSplittedTabBaseAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(OpenInSplittedTabBaseAction.class);

    private GotoDeclarationAction gotoDeclarationAction; // used as alternative, when no PsiElement is found as target
    private Boolean closePreviousTab;

    OpenInSplittedTabBaseAction(Boolean closePreviousTab) {
        this.closePreviousTab = closePreviousTab;
        this.gotoDeclarationAction = new GotoDeclarationAction();
    }

    private static int getUsagesPageSize() {
        return Math.max(1, Registry.intValue("ide.usages.page.size", 100));
    }

    private static boolean startFindUsages(@NotNull Editor editor, @NotNull Project project, PsiElement element) {
        if (element == null) {
            return false;
        }
        if (DumbService.getInstance(project).isDumb()) {
            AnAction action = ActionManager.getInstance().getAction(ShowUsagesAction.ID);
            String name = action.getTemplatePresentation().getText();
            DumbService.getInstance(project).showDumbModeNotification(ActionUtil.getUnavailableMessage(name, false));
        } else {
            RelativePoint popupPosition = JBPopupFactory.getInstance().guessBestPopupLocation(editor);
            new ShowUsagesAction().startFindUsages(element, popupPosition, editor, getUsagesPageSize());
        }
        return true;
    }

    public void actionPerformed(AnActionEvent e) {
        PsiFile file = e.getData(LangDataKeys.PSI_FILE);
        Editor editor = e.getData(PlatformDataKeys.EDITOR);

        if (file == null || editor == null) {
            e.getPresentation().setEnabled(false);
            return;
        }

        int offset = editor.getCaretModel().getOffset();

        PsiElement elementAt = file.findElementAt(offset);

        if (elementAt == null) {
            e.getPresentation().setEnabled(false);
            return;
        }

        Project project = elementAt.getProject();
        final PsiElement[] elements = getTargets(editor, project, offset);

        // if we got a valid symbol we will open it in a splitted tab, else we call the GotoDeclarationAction
        if (elements != null) {
            final FileEditorManagerEx fileEditorManager = FileEditorManagerEx.getInstanceEx(project);
            final EditorWindow nextWindowPane = receiveNextWindowPane(project, fileEditorManager, e.getDataContext());
            if (nextWindowPane == null) {
                return;
            }
            if (elements.length != 1) {
                if (elements.length == 0 && suggestCandidates(TargetElementUtil.findReference(editor, offset)).isEmpty()) {
                    PsiElement element = GotoDeclarationAction.findElementToShowUsagesOf(editor, editor.getCaretModel().getOffset());
                    if (startFindUsages(editor, project, element)) {
                        return;
                    }

                    //disable 'no declaration found' notification for keywords
                    if (isKeywordUnderCaret(project, file, offset)) return;
                }
                chooseAmbiguousTarget(editor, offset, elements, nextWindowPane);
            } else {
                final PsiElement element = elements[0];
                fileEditorManager.setCurrentWindow(nextWindowPane);
                // We want to replace the current active tab inside the splitter instead of creating a new tab.
                // So, we save which file is currently open, open the new file (in a new tab) and then close the
                // previous tab. To do this, we save which file is currently open.
                final VirtualFile fileToClose = fileEditorManager.getCurrentFile();
                // use the openFileImpl2-method instead of the openFile-method, as the openFile-method would open a new
                // window when the assigned shortcut for this action includes the shift-key
                nextWindowPane.getManager().openFileImpl2(nextWindowPane, element.getContainingFile().getVirtualFile(), true);
                // Of course, we don't want to close the tab if the new element is inside the same file as before.
                if (this.closePreviousTab && fileToClose != null && !fileToClose.equals(element.getContainingFile().getVirtualFile())) {
                    fileEditorManager.getCurrentWindow().closeFile(fileToClose);
                }

                scrollToTarget(element, nextWindowPane);
            }
        } else {
            this.gotoDeclarationAction.actionPerformed(e);
        }
    }

    private static boolean isKeywordUnderCaret(@NotNull Project project, @NotNull PsiFile file, int offset) {
        final PsiElement elementAtCaret = file.findElementAt(offset);
        if (elementAtCaret == null) return false;
        final NamesValidator namesValidator = LanguageNamesValidation.INSTANCE.forLanguage(elementAtCaret.getLanguage());
        return namesValidator != null && namesValidator.isKeyword(elementAtCaret.getText(), project);
    }

    @NotNull
    private static Collection<PsiElement> suggestCandidates(@Nullable PsiReference reference) {
        if (reference == null) {
            return Collections.emptyList();
        }
        return TargetElementUtil.getInstance().getTargetCandidates(reference);
    }

    // returns true if processor is run or is going to be run after showing popup
    private static boolean chooseAmbiguousTarget(@NotNull Editor editor,
                                                 int offset,
                                                 @NotNull PsiElementProcessor<? super PsiElement> processor,
                                                 @NotNull String titlePattern,
                                                 @Nullable PsiElement[] elements) {
        if (TargetElementUtil.inVirtualSpace(editor, offset)) {
            return false;
        }

        final PsiReference reference = TargetElementUtil.findReference(editor, offset);

        if (elements == null || elements.length == 0) {
            elements = reference == null ? PsiElement.EMPTY_ARRAY
                    : PsiUtilCore.toPsiElementArray(
                    underModalProgress(reference.getElement().getProject(),
                            () -> suggestCandidates(reference)));
        }

        if (elements.length == 1) {
            PsiElement element = elements[0];
            LOG.assertTrue(element != null);
            processor.execute(element);
            return true;
        }
        if (elements.length > 1) {
            String title;

            if (reference == null) {
                title = titlePattern;
            } else {
                final TextRange range = reference.getRangeInElement();
                final String elementText = reference.getElement().getText();
                LOG.assertTrue(range.getStartOffset() >= 0 && range.getEndOffset() <= elementText.length(), Arrays.toString(elements) + ";" + reference);
                final String refText = range.substring(elementText);
                title = MessageFormat.format(titlePattern, refText);
            }

            NavigationUtil.getPsiElementPopup(elements, new DefaultPsiElementCellRenderer(), title, processor).showInBestPositionFor(editor);
            return true;
        }
        return false;
    }

    private static void chooseAmbiguousTarget(final Editor editor, int offset, PsiElement[] elements, EditorWindow nextWindowPane) {
        if (!editor.getComponent().isShowing()) return;
        PsiElementProcessor<PsiElement> navigateProcessor = element -> {
            scrollToTarget(element, nextWindowPane);
            return true;
        };
        boolean found =
                chooseAmbiguousTarget(editor, offset, navigateProcessor, CodeInsightBundle.message("declaration.navigation.title"), elements);
        if (!found) {
            HintManager.getInstance().showErrorHint(editor, "Cannot find declaration to go to");
        }
    }

    private static void scrollToTarget(PsiElement target, EditorWindow nextWindowPane) {
        // defer the scrolling of the new tab, otherwise the scrolling may not work properly
        Timer delayingScrollToCaret = new Timer(10, actionEvent -> {
            if (!nextWindowPane.isShowing()) {
                scrollToTarget(target, nextWindowPane);
            } else {
                nextWindowPane.setAsCurrentWindow(true);
                Editor selectedTextEditor = nextWindowPane.getManager().getSelectedTextEditor();
                if (selectedTextEditor != null) {
                    selectedTextEditor.getCaretModel().moveToOffset(target.getTextOffset());
                    selectedTextEditor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                }
            }
        });
        delayingScrollToCaret.setRepeats(false);
        delayingScrollToCaret.start();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiFile file = e.getData(LangDataKeys.PSI_FILE);
        Editor editor = e.getData(PlatformDataKeys.EDITOR);

        if (file == null || editor == null) {
            e.getPresentation().setEnabled(false);
            return;
        }

        int offset = editor.getCaretModel().getOffset();

        PsiElement elementAt = file.findElementAt(offset);

        if (elementAt == null) {
            e.getPresentation().setEnabled(false);
            return;
        }

        Project project = elementAt.getProject();
        PsiElement[] target = getTargets(editor, project, offset);
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
    private PsiElement[] getTargets(Editor editor, Project project, int offset) {

        PsiElement[] allTargetElements = underModalProgress(project, () ->
                GotoDeclarationAction.findAllTargetElements(project, editor, offset));
        return allTargetElements;
    }

    private static <T> T underModalProgress(@NotNull Project project,
                                            @NotNull Computable<T> computable) throws ProcessCanceledException {
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            DumbService.getInstance(project).setAlternativeResolveEnabled(true);
            try {
                return ApplicationManager.getApplication().runReadAction(computable);
            } finally {
                DumbService.getInstance(project).setAlternativeResolveEnabled(false);
            }
        }, "Resolving Reference...", true, project);
    }
}
