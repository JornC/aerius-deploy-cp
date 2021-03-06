package nl.yogh.aerius.wui.builder.ui.pulls;

import com.google.inject.ImplementedBy;

import nl.yogh.aerius.builder.domain.CommitInfo;
import nl.yogh.aerius.wui.builder.ui.pulls.PullRequestView.Presenter;
import nl.yogh.gwt.wui.activity.View;

@ImplementedBy(PullRequestViewImpl.class)
public interface PullRequestView extends View<Presenter> {
  public interface Presenter {

  }

  public void insertRepositoryCommit(CommitInfo info);

  public void removePullRequest(String leftOver);

}
